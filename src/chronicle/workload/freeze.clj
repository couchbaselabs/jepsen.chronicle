(ns chronicle.workload.freeze
  (:require [chronicle
             [nemesis :as nemesis]
             [workload-util :as workload-util]]
            [jepsen
             [generator :as gen]]))

(defn nemesis-gen
  []
  [(gen/sleep 5)
   {:type :info :f :start}
   (gen/sleep 5)
   {:type :info :f :stop}
   (gen/sleep 10)])

(defn freeze-workload
  [opts]
  {:nemesis (nemesis/node-freeze)
   :generator (gen/nemesis
               (nemesis-gen)
               (gen/time-limit 45 (workload-util/client-gen opts)))})
