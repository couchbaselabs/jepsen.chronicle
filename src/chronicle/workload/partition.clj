(ns chronicle.workload.partition
  (:require [chronicle
             [nemesis :as nemesis]
             [util :as util]
             [workload-util :as workload-util]]
            [jepsen
             [generator :as gen]]))

(defn nemesis-gen-cycle-fn
  [test ctx]
  (let [node (util/get-one-ok-node test)]
    [(gen/sleep 10)
     {:type :info :f :isolate-completely :value [node]}
     (gen/sleep 10)
     {:type :info :f :heal-network}]))

(defn partition-workload
  [opts]
  {:nemesis (nemesis/network-partition)
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen-cycle-fn)
                   (gen/time-limit (:time-limit opts)))})
