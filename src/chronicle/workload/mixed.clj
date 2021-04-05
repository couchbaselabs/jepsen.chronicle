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
  (let [ok-nodes (util/get-nodes-with-status test :ok)
        ok-count (count ok-nodes)

        test-nodes   (count (:nodes test))
        disrupted    (- test-nodes ok-count)
        max-minority (quot (dec test-nodes) 2)
        can-disrupt  (< disrupted max-minority)

        actions (keep
                 (fn [[node status]]
                   (case status
                     :ok (if can-disrupt
                           {:type :info
                            :value [node]
                            :f (rand-nth [:remove :crash :freeze])})

                     ;; Repair Removal
                     :removed {:type :info :value [node] :f :wipe}
                     :wiped {:type :info :value [node] :f :join}

                     ;; Repair crash and freeze
                     :killed {:type :info :value [node] :f :restart}
                     :frozen {:type :info :value [node] :f :resume}))
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
              #{:freeze :resume} (nemesis/node-freeze)})
   :generator (->> (workload-util/client-gen opts)
                   (gen/nemesis nemesis-gen)
                   (gen/time-limit (:time-limit opts)))})
