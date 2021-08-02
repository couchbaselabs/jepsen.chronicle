(ns chronicle.workload.mixed
  (:require [chronicle
             [nemesis :as nemesis]
             [util :as util]
             [workload-util :as workload-util]]
            [slingshot.slingshot :refer [throw+]]
            [jepsen
             [generator :as gen]
             [nemesis :refer [compose]]]))

(defn nemesis-gen
  [test ctx]
  (let [ok-nodes (util/get-ok-nodes test)
        ok-count (count ok-nodes)

        test-nodes   (count (:nodes test))
        disrupted    (- test-nodes ok-count)
        max-minority (quot (dec test-nodes) 2)
        can-disrupt  (< disrupted max-minority)

        actions (reduce
                 (fn [actions [node status]]
                   (concat
                    actions
                    (map (fn [f] {:type :info :f f :value [node]})
                         (case status
                           ;; Disrupt healthy
                           #{} (if can-disrupt
                                 [:remove :crash :freeze :isolate-completely])

                           ;; Repair removal, partitioning after removal is
                           ;; unlikely to be interesting
                           #{:removed} [:wipe]
                           #{:removed :partitioned} [:wipe]
                           #{:removed :wiped} [:join]
                           ;; Need to heal before attempting to rejoin
                           #{:removed :partitioned :wiped} [:heal-network]

                           ;; Repair crash, add partition
                           #{:killed} [:restart :isolate-completely]
                           #{:killed :frozen} [:restart :isolate-completely]
                           #{:killed :partitioned} [:restart :heal-network]
                           #{:killed :frozen :partitioned} [:restart]
                           ;; Unfreeze, crash, or add partition
                           #{:frozen} [:resume :isolate-completely :crash]
                           #{:frozen :partitioned} [:resume :heal-network :crash]

                           ;; Repair partition, or add some other disruption
                           #{:partitioned} [:remove :crash :freeze :heal-network]

                           []))))
                 []
                 @(:membership test))]
    (if (empty? actions)
      (throw+ {:error "No valid actions?"
               :state @(:membership test)})
      [(rand-nth actions)
       (gen/sleep 5)])))

(defn mixed-workload
  [opts]
  {:nemesis (compose
             {#{:remove :wipe :join} (nemesis/node-removal)
              #{:crash :restart} (nemesis/node-crash)
              #{:freeze :resume} (nemesis/node-freeze)
              #{:isolate-completely :heal-network} (nemesis/network-partition)})
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen)
                   (gen/time-limit (:time-limit opts)))})
