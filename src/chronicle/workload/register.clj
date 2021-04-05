(ns chronicle.workload.register
  (:require [chronicle
             [workload-util :as workload-util]]
            [jepsen
             [nemesis :as nemesis]
             [generator :as gen]]))

(defn register-workload
  [opts]
  {:nemesis nemesis/noop
   :generator (->> (workload-util/client-gen opts)
                   (gen/clients)
                   (gen/time-limit (:time-limit opts)))})
