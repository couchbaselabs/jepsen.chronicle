(ns chronicle.core
  (:require [chronicle
             [cli :as chronicle-cli]
             [client :as client]
             [sanitychecker :as sanitychecker]
             [seqchecker :as seqchecker]
             [util :as util]
             [workload :as workload]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn error fatal]]
            [jepsen
             [checker :as checker]
             [cli :as cli]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [independent :as indep]
             [nemesis :as nemesis]
             [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.nemesis.time]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]))

(defn chronicle-db
  []
  (reify
    db/DB
    (setup!    [_ test node] (util/setup-node test))
    (teardown! [_ test node] (util/teardown-node))

    db/Primary
    (setup-primary! [_ test node] (util/setup-cluster test node))

    db/LogFiles
    (log-files [_ test node]
      (try+
       (str/split-lines
        (c/exec :find "/home/vagrant/chronicle/cluster" :-type :f))
       (catch [:type :jepsen.control/nonzero-exit] e
         (if (some->> e :err (re-find #"No such file or directory"))
           (warn "Getting log files failed, log directory doesn't exist?")
           (error "Encountered exception while trying to get log files:" e)))
       (catch Exception e
         (error "Encountered exception while trying to get log files:" e))))))

;; Testcase setup
(defn chronicle-test
  "Run the test"
  [opts]
  (merge tests/noop-test
         opts
         {:name (->> opts :workload name (str "Chronicle-"))
          :pure-generators true
          :db (chronicle-db)
          :client (client/base-client)
          :membership (atom (zipmap (:nodes opts) (repeat #{})))
          :checker (case (:client-type opts)
                     :reg-gen
                     (checker/compose
                      {:indep (indep/checker
                               (checker/compose
                                {:timeline (timeline/html)
                                 :linear (checker/linearizable
                                          {:model (model/cas-register :KeyNotFound)})
                                 :sequential (seqchecker/sequential)}))
                       :sanity (sanitychecker/sanity-check)
                       :perf (checker/perf)})

                     :txn-gen
                     (checker/compose
                      {:linear (checker/linearizable
                                {:model (model/multi-register
                                         (zipmap (range (:txn-keys opts))
                                                 (repeat :KeyNotFound)))})
                       :sanity (sanitychecker/sanity-check)
                       :perf (checker/perf)}))}
         (workload/get-workload opts)))

(defn chronicle-suite
  "Run a suite of tests, reading the test parameters from an edn file. Note
  that this bypasses the cli parser, allowing for arbitrary value injection
  into the test map where desirable. This does, however, means that values need
  to be passed pre-parsed."
  [opts]
  (->> opts
       :suite
       io/reader
       java.io.PushbackReader.
       edn/read
       (map #(merge opts %))
       (map-indexed (if-not (opts :keep-install)
                      #(do %2)
                      #(if (= 0 %1) %2 (dissoc %2 :install))))
       (map chronicle-test)))

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

  ;; The default resolution of the perf graphs is tiny, so render something
  ;; bigger to show more detail
  (alter-var-root
   (var jepsen.checker.perf/preamble)
   (fn [preamble]
     (fn [output-path]
       (assoc-in (vec (preamble output-path)) [1 5 :xs] '(1800 800)))))

  ;; Parse args and run the test
  (cli/run! (merge (cli/single-test-cmd
                    {:test-fn chronicle-test
                     :opt-spec chronicle-cli/extra-cli-opts})
                   (cli/test-all-cmd
                    {:tests-fn chronicle-suite
                     :opt-spec chronicle-cli/suite-cli-opts})
                   (cli/serve-cmd))
            args))
