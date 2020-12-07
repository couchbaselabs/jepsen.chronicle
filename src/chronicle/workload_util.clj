(ns chronicle.workload-util
  (:require [chronicle
             [util :as util]]
            [jepsen
             [generator :as gen]
             [independent :as independent]]))

(defn base-client-gen
  "Returns a client op generator for a single key"
  []
  (gen/phases
   (gen/once {:f :write :value 0 :f-type :put})
   (gen/mix [(gen/repeat {:f :read})
             (map (fn [x] {:f :write
                           :value x
                           :f-type :post})
                  (drop 1 (range)))])))

(defn- rand-int-n-2n
  "Return a random integer between n and 2n"
  [n]
  (+ n (rand-int n)))

(defn client-gen
  "Return a simple client op generator"
  [opts]
  (independent/concurrent-generator
   (:doc-threads opts)
   (range)
   (fn [_]
     (cond->> (base-client-gen)
       (pos? (:rate opts 0)) (gen/stagger (/ (:rate opts)))

       (pos? (:doc-op-limit opts 0)) (gen/limit
                                      (rand-int-n-2n
                                       (/ (:doc-op-limit opts) 2)))))))
