(ns chronicle.workload.register
  (:require [chronicle
             [workload-util :as workload-util]]
            [jepsen
             [nemesis :as nemesis]
             [generator :as gen]]))

(defn register-workload
  [opts]
  {:nemesis nemesis/noop
   :generator (gen/clients (gen/time-limit 45 (workload-util/client-gen opts)))})
