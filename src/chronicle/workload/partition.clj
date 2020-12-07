(ns chronicle.workload.partition
  (:require [chronicle
             [workload-util :as workload-util]]
            [jepsen
             [nemesis :as nemesis]
             [generator :as gen]]))

(defn nemesis-gen
  []
  [(gen/sleep 5)
   {:type :info :f :start}
   (gen/sleep 5)
   {:type :info :f :stop}
   (gen/sleep 10)])

(defn partition-workload
  [opts]
  {:nemesis (nemesis/partition-random-node)
   :generator (gen/nemesis
               (nemesis-gen)
               (gen/time-limit 45 (workload-util/client-gen opts)))})
