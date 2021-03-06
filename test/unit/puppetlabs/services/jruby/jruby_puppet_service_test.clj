(ns puppetlabs.services.jruby.jruby-puppet-service-test
  (:import (com.puppetlabs.puppetserver JRubyPuppet))
  (:require [clojure.test :refer :all]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-service :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as services]
            [clojure.stacktrace :as stacktrace]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [me.raynes.fs :as fs]
            [schema.test :as schema-test]))

(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)
(use-fixtures :once schema-test/validate-schemas)

(defn jruby-service-test-config
  [pool-size]
  (jruby-testutils/jruby-puppet-tk-config
    (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})))

(defn jruby-service-test-config-with-timeouts
  [connect-timeout idle-timeout]
  (merge (jruby-service-test-config 1)
         {:http-client {:connect-timeout-milliseconds connect-timeout
                        :idle-timeout-milliseconds    idle-timeout}}))

(def default-services
  [jruby-puppet-pooled-service
   profiler/puppet-profiler-service])

(deftest test-error-during-init
  (testing
      (str "If there as an exception while putting a JRubyPuppet instance in "
           "the pool the application should shut down.")
    (logging/with-test-logging
      (with-redefs [jruby-internal/create-pool-instance!
                    (fn [& _] (throw (Exception. "42")))]
                   (let [got-expected-exception (atom false)]
                     (try
                       (bootstrap/with-app-with-config
                         app
                         default-services
                         (jruby-service-test-config 1)
                         (tk/run-app app))
                       (catch Exception e
                         (let [cause (stacktrace/root-cause e)]
                           (is (= (.getMessage cause) "42"))
                           (reset! got-expected-exception true))))
                     (is (true? @got-expected-exception)
                                "Did not get expected exception."))
                   (is (logged?
                         #"^shutdown-on-error triggered because of exception!"
                                :error))))))

(deftest test-pool-size
  (testing "The pool is created and the size is correctly reported"
    (let [pool-size 2]
      (bootstrap/with-app-with-config
        app
        default-services
        (jruby-service-test-config pool-size)
        (let [service (app/get-service app :JRubyPuppetService)
              all-the-instances
              (mapv (fn [_] (jruby-protocol/borrow-instance service :test-pool-size))
                    (range pool-size))]
          (is (= 0 (jruby-protocol/free-instance-count service)))
          (is (= pool-size (count all-the-instances)))
          (doseq [instance all-the-instances]
            (is (not (nil? instance))
                "One of the JRubyPuppet instances retrieved from the pool is nil")
            (jruby-protocol/return-instance service instance :test-pool-size))
          (is (= pool-size (jruby-protocol/free-instance-count service))))))))

(deftest test-pool-population-during-init
  (testing "A JRuby instance can be borrowed from the 'init' phase of a service"
    (let [test-service (tk/service
                         [[:JRubyPuppetService borrow-instance return-instance]]
                         (init [this context]
                               (return-instance
                                 (borrow-instance :test-pool-population)
                                 :test-pool-population)
                               context))]

      ; Bootstrap TK, causing the 'init' function above to be executed.
      (tk/boot-services-with-config
        (conj default-services test-service)
        (jruby-service-test-config 1))

      ; If execution gets here, the test passed.
      (is (true? true)))))

(deftest test-with-jruby-puppet
  (testing "the `with-jruby-puppet macro`"
    (bootstrap/with-app-with-config
      app
      default-services
      (jruby-service-test-config 1)
      (let [service (app/get-service app :JRubyPuppetService)]
        (with-jruby-puppet
          jruby-puppet
          service
          :test-with-jruby-puppet
          (is (instance? JRubyPuppet jruby-puppet))
          (is (= 0 (jruby-protocol/free-instance-count service))))
        (is (= 1 (jruby-protocol/free-instance-count service)))
        ;; borrow and return one more time: we're using `with-jruby-puppet`
        ;; here even though it looks a bit strange, because that is what this
        ;; test is intended to cover.
        (with-jruby-puppet
          jruby-puppet
          service
          :test-with-jruby-puppet)
        (let [jruby (jruby-protocol/borrow-instance service :test-with-jruby-puppet)]
          ;; the counter gets incremented when the instance is returned to the
          ;; pool, so right now it should be at 2 since we've called
          ;; `with-jruby-puppet` twice.
          (is (= 2 (:borrow-count (jruby-core/instance-state jruby))))
          (jruby-protocol/return-instance service jruby :test-with-jruby-puppet))))))

(deftest test-jruby-events
  (testing "jruby service sends event notifications"
    (let [counter (atom 0)
          requested (atom {})
          borrowed (atom {})
          returned (atom {})
          callback (fn [{:keys [type reason requested-event instance] :as event}]
                     (case type
                       :instance-requested
                       (reset! requested {:sequence (swap! counter inc)
                                          :event event
                                          :reason reason})

                       :instance-borrowed
                       (reset! borrowed {:sequence (swap! counter inc)
                                         :reason reason
                                         :requested-event requested-event
                                         :instance instance})

                       :instance-returned
                       (reset! returned {:sequence (swap! counter inc)
                                         :reason reason
                                         :instance instance})))
          event-service (tk/service [[:JRubyPuppetService register-event-handler]]
                          (init [this context]
                            (register-event-handler callback)
                            context))]
      (bootstrap/with-app-with-config
        app
        (conj default-services event-service)
        (jruby-service-test-config 1)
        (let [service (app/get-service app :JRubyPuppetService)]
          ;; We're making an empty call to `with-jruby-puppet` here, because
          ;; we want to trigger a borrow/return via the same code path that
          ;; would be used in production.
          (with-jruby-puppet
            jruby-puppet
            service
            :test-jruby-events)
          (is (= {:sequence 1 :reason :test-jruby-events}
                (dissoc @requested :event)))
          (is (= {:sequence 2 :reason :test-jruby-events}
                (dissoc @borrowed :instance :requested-event)))
          (is (jruby-schemas/jruby-puppet-instance? (:instance @borrowed)))
          (is (identical? (:event @requested) (:requested-event @borrowed)))
          (is (= {:sequence 3 :reason :test-jruby-events}
                (dissoc @returned :instance)))
          (is (= (:instance @borrowed) (:instance @returned)))
          (with-jruby-puppet
            jruby-puppet
            service
            :test-jruby-events)
          (is (= 4 (:sequence @requested)))
          (is (= 5 (:sequence @borrowed)))
          (is (= 6 (:sequence @returned))))))))

(deftest test-borrow-timeout-configuration
  (testing "configured :borrow-timeout is honored by the borrow-instance service function"
    (let [timeout   250
          pool-size 1
          config  (jruby-testutils/jruby-puppet-tk-config
                    (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size
                                                          :borrow-timeout timeout}))]
      (bootstrap/with-app-with-config
        app
        default-services
        config
        (let [service (app/get-service app :JRubyPuppetService)
              context (services/service-context service)
              pool-context (:pool-context context)]
          (let [jrubies (jruby-testutils/drain-pool pool-context pool-size)]
            (is (= 1 (count jrubies)))
            (is (every? jruby-schemas/jruby-puppet-instance? jrubies)))
          (let [test-start-in-millis (System/currentTimeMillis)]
            (is (nil? (jruby-protocol/borrow-instance service :test-borrow-timeout-configuration)))
            (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout))
            (is (= (:borrow-timeout context) timeout)))))))

  (testing (str ":borrow-timeout defaults to " jruby-core/default-borrow-timeout " milliseconds")
    (bootstrap/with-app-with-config
      app
      default-services
      (jruby-service-test-config 1)
      (let [service (app/get-service app :JRubyPuppetService)
            context (services/service-context service)]
        (is (= (:borrow-timeout context) jruby-core/default-borrow-timeout))))))

(deftest timeout-settings-applied
  (testing "timeout settings are properly plumbed"
    (let [connect-timeout 42
          socket-timeout  55]
      (bootstrap/with-app-with-config
        app
        default-services
        (jruby-service-test-config-with-timeouts connect-timeout socket-timeout)
        (let [service          (app/get-service app :JRubyPuppetService)
              context          (services/service-context service)
              pool-context-cfg (get-in context [:pool-context :config])]
          (is (= connect-timeout (:http-client-connect-timeout-milliseconds pool-context-cfg)))
          (is (= socket-timeout  (:http-client-idle-timeout-milliseconds pool-context-cfg)))))))

  (testing "default values are set"
    (bootstrap/with-app-with-config
      app
      default-services
      (jruby-service-test-config 1)
      (let [service          (app/get-service app :JRubyPuppetService)
            context          (services/service-context service)
            pool-context-cfg (get-in context [:pool-context :config])]
        (is (= jruby-core/default-http-connect-timeout
               (:http-client-connect-timeout-milliseconds pool-context-cfg)))
        (is (= jruby-core/default-http-socket-timeout
               (:http-client-idle-timeout-milliseconds pool-context-cfg)))))))

(deftest facter-jar-loaded-during-init
  (testing (str "facter jar found from the ruby load path is properly "
             "loaded into the system classpath")
    (let [temp-dir (ks/temp-dir)
          facter-jar (-> temp-dir
                       (fs/file jruby-core/facter-jar)
                       (fs/absolute-path))]
      (fs/touch facter-jar)
      (bootstrap/with-app-with-config
        app
        [jruby-puppet-pooled-service profiler/puppet-profiler-service]
        (assoc-in (jruby-service-test-config 1)
          [:jruby-puppet :ruby-load-path]
          (into [] (cons (fs/absolute-path temp-dir)
                     jruby-testutils/ruby-load-path)))
        (is (true? (some #(= facter-jar (.getFile %))
                     (.getURLs (ClassLoader/getSystemClassLoader)))))))))
