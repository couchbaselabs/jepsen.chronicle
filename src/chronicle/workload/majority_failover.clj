(ns chronicle.workload.majority-failover
  (:require [chronicle
             [nemesis :as nemesis]
             [util :as util]
             [workload-util :as workload-util]]
            [jepsen
             [generator :as gen]]))

(defn nemesis-gen-cycle-fn
  [test ctx]
  (let [sel_c (-> test :nodes count (quot 2) inc)
        nodes (->> test :nodes shuffle (take sel_c))]
    [(gen/sleep 5)
     {:type :info :f :failover :value nodes}
     (gen/sleep 10)
     {:type :info :f :wipe :value nodes}
     (gen/sleep 5)
     {:type :info :f :join :value nodes}
     (gen/sleep 5)]))

(defn mfailover-workload
  [opts]
  {:nemesis (nemesis/node-removal)
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen-cycle-fn)
                   (gen/time-limit (:time-limit opts)))})
