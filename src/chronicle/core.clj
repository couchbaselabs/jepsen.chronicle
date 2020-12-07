(ns chronicle.core
  (:require [chronicle
             [cli :as chronicle-cli]
             [client :as client]
             [seqchecker :as seqchecker]
             [util :as util]]
            [clojure.tools.logging :refer [info warn error fatal]]
            [jepsen
             [checker :as checker]
             [cli :as cli]
             [db :as db]
             [generator :as gen]
             [independent :as indep]
             [nemesis :as nemesis]
             [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.nemesis.time]
            [knossos.model :as model]))

(defn chronicle-db
  []
  (reify
    db/DB
    (setup!    [_ test node] (util/setup-node test))
    (teardown! [_ test node] (util/teardown-node))

    db/Primary
    (setup-primary! [_ test node] (util/setup-cluster test node))))

;; Simple test generator until we write real tests
(defn simple-gen
  []
  (indep/concurrent-generator
   5 ; Threads per key
   (range)
   (fn [_]
     (gen/phases
      (gen/once {:f :write :value 0 :f-type :put})
      (gen/mix [(gen/repeat {:f :read})
                (map (fn [x] {:f :write :value x :f-type :post}) (drop 1 (range)))])))))

;; Testcase setup
(defn chronicle-test
  "Run the test"
  [opts]
  (merge tests/noop-test
         opts
         {:name "Chronicle"
          :pure-generators true
          :db (chronicle-db)
          :nemesis nemesis/noop
          :client (client/base-client)
          :generator (gen/clients (gen/time-limit 30 (simple-gen)))
          :checker (checker/compose
                    {:indep (indep/checker
                             (checker/compose
                              {:timeline (timeline/html)
                               :linear (checker/linearizable
                                        {:model (model/cas-register -1)})
                               :sequential (seqchecker/sequential)}))
                     :perf (checker/perf)})}))

(defn -main
  "Run the test specified by the cli arguments"
  [& args]

  ;; Jepsen's fressian writer crashes the entire process if it encounters something
  ;; it doesn't know how to log, preventing the results from being analysed. We
  ;; don't really care about fressian output, so just log a warning and continue
  (alter-var-root
   (var jepsen.store/write-fressian!)
   (fn [real_fressian!]
     (fn [& args]
       (try
         (apply real_fressian! args)
         (catch Exception e
           (error "Ignoring exception while attempting to write fressian" e))))))

  ;; Parse args and run the test
  (let [testData (cli/single-test-cmd {:test-fn chronicle-test
                                       :opt-spec chronicle-cli/extra-cli-opts})
        serve (cli/serve-cmd)]
    (cli/run! (merge testData serve) args)))
