(ns chronicle.workload.disk
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
     {:type :info :f :fail-disk :value [node]}
     (gen/sleep 5)
     {:type :info :f :recover-disk :value [node]}]))

(defn disk-workload
  [opts]
  {:requires-vdisk true
   :nemesis (nemesis/disk-nemesis)
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen-cycle-fn)
                   (gen/time-limit (:time-limit opts)))})
