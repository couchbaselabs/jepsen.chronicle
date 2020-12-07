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

;; (defn nemesis-gen
;;   "DUMMY PLACEHOLDER: TODO"
;;   [opts]
;;   [(gen/sleep 5)
;;    {:type :info :f :start}
;;    (gen/sleep 5)
;;    {:type :info :f :stop}
;;    (gen/sleep 10)
;;    {:type :info :f :start}
;;    (gen/sleep 5)
;;    {:type :info :f :stop}
;;    (gen/sleep 5)])

;; (defn get-workload
;;   "DUMMYFN"
;;   [opts]
;;   ;; FIXME FIXME
;;   {;:nemesis (nemesis/node-removal)
;;    ;:nemesis nil ;(jepnemesis/partition-random-node)
;;    :generator (gen/clients (gen/time-limit 45 (client-gen opts)))})
;;                            ;;(nemesis-gen nil))})
