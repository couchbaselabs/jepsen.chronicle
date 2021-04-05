(ns chronicle.workload.crash
  (:require [chronicle
             [nemesis :as nemesis]
             [util :as util]
             [workload-util :as workload-util]]
            [jepsen
             [generator :as gen]]))

(defn nemesis-gen-cycle-fn
  [test ctx]
  (let [node (util/get-one-ok-node test)]
    [(gen/sleep 5)
     {:type :info :f :crash :value [node]}
     (gen/sleep 5)
     {:type :info :f :restart :value [node]}]))

(defn crash-workload
  [opts]
  {:nemesis (nemesis/node-crash)
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen-cycle-fn)
                   (gen/time-limit (:time-limit opts)))})
