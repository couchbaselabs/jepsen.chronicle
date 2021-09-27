(ns chronicle.workload
  (:require [chronicle.workload.register :refer [register-workload]]
            [chronicle.workload.freeze :refer [freeze-workload]]
            [chronicle.workload.crash :refer [crash-workload]]
            [chronicle.workload.add-remove :refer [add-remove-workload]]
            [chronicle.workload.partition :refer [partition-workload]]
            [chronicle.workload.mixed :refer [mixed-workload]]
            [chronicle.workload.failover :refer [failover-workload]]
            [chronicle.workload.majority-failover :refer [mfailover-workload]]
            [chronicle.workload.disk :refer [disk-workload]]))

(def workloads-map
  {:register register-workload
   :freeze freeze-workload
   :crash crash-workload
   :addremove add-remove-workload
   :partition partition-workload
   :mixed mixed-workload
   :disk disk-workload
   :failover failover-workload
   :majorityfailover mfailover-workload})

(defn get-workload
  [opts]
  (let [workloadfn (workloads-map (:workload opts))]
    (workloadfn opts)))
