(ns chronicle.workload
  (:require [chronicle.workload.register :refer [register-workload]]
            [chronicle.workload.freeze :refer [freeze-workload]]
            [chronicle.workload.crash :refer [crash-workload]]
            [chronicle.workload.add-remove :refer [add-remove-workload]]
            [chronicle.workload.partition :refer [partition-workload]]))

(def workloads-map
  {:register register-workload
   :freeze freeze-workload
   :crash crash-workload
   :addremove add-remove-workload
   :partition partition-workload})

(defn get-workload
  [opts]
  (let [workloadfn (workloads-map (:workload opts))]
    (workloadfn opts)))
