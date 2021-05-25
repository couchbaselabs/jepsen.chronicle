(ns chronicle.workload-util
  (:require [chronicle
             [util :as util]]
            [jepsen
             [generator :as gen]
             [independent :as independent]]))

(defn- rand-int-n-2n
  "Return a random integer between n and 2n"
  [n]
  (+ n (rand-int n)))

(defn- base-reg-gen
  "Returns a register op generator for a single key"
  []
  (gen/phases
   (gen/once {:f :write :value 0 :f-type :put})
   (gen/mix [(gen/repeat {:f :read})
             (map (fn [x] {:f :write
                           :value x
                           :f-type :post})
                  (drop 1 (range)))])))

(defn- reg-gen
  "Return a register op generator"
  [opts]
  (independent/concurrent-generator
   (:doc-threads opts)
   (range)
   (fn [_]
     (cond->> (base-reg-gen)
       (pos? (:rate opts 0)) (gen/stagger (/ (:rate opts)))
       (pos? (:doc-op-limit opts 0)) (gen/limit
                                      (rand-int-n-2n
                                       (/ (:doc-op-limit opts) 2)))))))

(defn- base-txn-gen
  [opts ctx]
  (let [txn-size (:txn-size opts)
        txn-keys (->> (:txn-keys opts)
                      range
                      shuffle
                      (take txn-size))]
    {:f :txn
     :value (map (fn [k] (rand-nth [[:read k nil]
                                    [:write k (rand-int 30)]]))
                 txn-keys)}))

(defn- txn-gen
  "Returns a transaction generator"
  [opts]
  (cond->> base-txn-gen
    (pos? (:rate opts 0)) (gen/stagger (/ (:rate opts)))))

(defn client-gen
  "Returns a client op generator"
  [opts]
  (case (:client-type opts)
    :txn-gen (txn-gen opts)
    :reg-gen (reg-gen opts)))
