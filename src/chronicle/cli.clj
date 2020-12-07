(ns chronicle.cli
  (:require [chronicle.util :as util]
            [jepsen.cli :as jepsen.cli]))

(def consistency-levels #{"local" "leader" "quorum"})

(defn parse-int
  "Parse a string to an integer"
  [x]
  (Integer/parseInt x))

(def extra-cli-opts
  [[nil "--workload WORKLOAD"
    "The workload to run"]
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
    "Copy the given source directory onto the test nodes and compile it prior to starting the test. If this argument is not provided then a previously compile copy must already be present on the nodes"
    :parse-fn util/get-install-package]
   [nil "--consistency CONSISTENCY"
    "Request the given consistency level when performing read operations"
    :default "local"
    :validate [consistency-levels (jepsen.cli/one-of consistency-levels)]]])
