(ns chronicle.workload.freeze
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
     {:type :info :f :freeze :value [node]}
     (gen/sleep 10)
     {:type :info :f :resume :value [node]}]))

(defn freeze-workload
  [opts]
  {:nemesis (nemesis/node-freeze)
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen-cycle-fn)
                   (gen/time-limit (:time-limit opts)))})
