(ns chronicle.workload.add-remove
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
     {:type :info :f :remove :value [node]}
     (gen/sleep 3)
     {:type :info :f :wipe :value [node]}
     (gen/sleep 10)
     {:type :info :f :join :value [node]}
     (gen/sleep 5)]))

(defn add-remove-workload
  [opts]
  {:nemesis (nemesis/node-removal)
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen-cycle-fn)
                   (gen/time-limit (:time-limit opts)))})
