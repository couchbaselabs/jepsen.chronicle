(ns chronicle.sanitychecker
  (:require [jepsen.checker :as checker]))

(defn sanity-check
  "Return unknown validity if the test appears to be broken"
  []
  (reify checker/Checker
    (check [this test history opts]
      (cond
        ;; A nemesis exception indicates that our workloads aren't functioning
        ;; correctly (or maybe not at all). We should return unknown test
        ;; to avoid a broken nemesis/workload from silently passing
        (some #(and (= (:process %) :nemesis)
                    (contains? % :exception))
              history)
        {:valid? :unknown
         :error "Nemesis crashed during test, workload broken?"}

        :else
        {:valid? true}))))
