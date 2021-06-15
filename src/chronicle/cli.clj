(ns chronicle.cli
  (:require [chronicle
             [util :as util]
             [workload :as workload]]
            [clojure.java.io :as io]
            [jepsen.cli :as jepsen.cli]))

(def consistency-levels #{"local" "leader" "quorum"})

(defn parse-int
  "Parse a string to an integer"
  [x]
  (Integer/parseInt x))

(def extra-cli-opts
  [[nil "--workload WORKLOAD"
    (str "The workload to run. " (jepsen.cli/one-of workload/workloads-map))
    :parse-fn keyword
    :missing (str "--workload " (jepsen.cli/one-of workload/workloads-map))
    :validate [workload/workloads-map (jepsen.cli/one-of workload/workloads-map)]]
   [nil "--doc-threads THREADS"
    "Number of worker threads per key"
    :default 3
    :parse-fn parse-int]
   [nil "--doc-op-limit LIMIT"
    "Maximum number of ops to be executed before retiring a key. Set to 0 to never retire keys"
    :default 0
    :parse-fn parse-int]
   [nil "--rate RATE"
    "Limit the rate of operations to approximately rate operations per second. Set to 0 to disable rate limiting"
    :default 0
    :parse-fn parse-int]
   [nil "--install SRC_DIR"
    (str "Copy the given source directory onto the test nodes and compile it prior to "
         "starting the test. If this argument is not provided then a previously compiled "
         "copy must already be present on the nodes.")
    :parse-fn util/get-install-package]
   [nil "--consistency CONSISTENCY"
    (str "Request the given consistency level when performing read operations. "
         (jepsen.cli/one-of consistency-levels))
    :default "quorum"
    :validate [consistency-levels (jepsen.cli/one-of consistency-levels)]]
   [nil "--client-stickiness STICKINESS"
    "Client stickiness"
    :default :sticky
    :parse-fn keyword
    :validate (let [sticky-levels #{:sticky :any-node :healthy-nodes}]
                [sticky-levels (jepsen.cli/one-of sticky-levels)])]
   [nil "--client-type CLIENT-TYPE"
    "Set the type of client operations to be performed"
    :default :reg-gen
    :parse-fn keyword
    :validate (let [client-types #{:reg-gen :txn-gen}]
                [client-types (jepsen.cli/one-of client-types)])]
   [nil "--txn-keys KEYS"
    "Number of keys transactions can act upon"
    :default 10
    :parse-fn parse-int]
   [nil "--txn-size SIZE"
    "Number of sub-operations in each transaction"
    :default 5
    :parse-fn parse-int]
   [nil "--txn-retries RETRIES"
    "Number of time a transaction that encountered a conflict will get retried"
    :default 25
    :parse-fn parse-int]])

(def suite-cli-opts
  (jepsen.cli/merge-opt-specs
   extra-cli-opts
   [[nil "--suite SUITE"
     "The suite of tesys to run"
     :missing "A --suite file must be provided when using the test-all command"
     :validate [#(->> % io/file .exists) "Provided suite file not found"]]
    [nil "--workload WORKLOAD"
     "Not used for test-all commad"
     :default nil
     :validate [(constantly false) "Workload option is not used for test-all suites"]]
    [nil "--[no-]keep-install"
     "Re-use the chronicle build/install from the first test for all subsequent tests"
     :default true]]))
