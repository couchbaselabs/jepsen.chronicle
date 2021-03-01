(ns chronicle.nemesis
  (:require [chronicle.util :as util]
            [jepsen
             [control :as c]
             [nemesis :as nemesis]]
            [jepsen.control.util :as cu]))

(defn node-freeze
  ([] (node-freeze rand-nth))
  ([targeter]
   (nemesis/node-start-stopper
    targeter
    (fn start [t n]
      (c/exec :pkill :-SIGSTOP :beam.smp :-P (c/lit "$(< /tmp/chronicle.pid)"))
      (swap! (:membership t) assoc n :frozen)
      :paused)
    (fn stop [t n]
      (c/exec :pkill :-SIGCONT :beam.smp :-P (c/lit "$(< /tmp/chronicle.pid)"))
      (swap! (:membership t) assoc n :ok)
      :resumed))))

(defn node-crash
  ([] (node-crash rand-nth))
  ([targeter]
   (nemesis/node-start-stopper
    targeter
    (fn start [t n]
      (c/exec :pkill :-SIGKILL :-P (c/lit "$(< /tmp/chronicle.pid)"))
      (cu/stop-daemon! "/tmp/chonicle.pid")
      (swap! (:membership t) assoc n :killed)
      :killed)
    (fn stop [t n]
      (util/start-daemon)
      (swap! (:membership t) assoc n :ok)
      :restarted))))

(defn node-removal
  ([] (node-removal rand-nth))
  ([targeter]
   (nemesis/node-start-stopper
    targeter
    (fn start [t n]
      ;; FIXME/TODO: The following will occasionally break if targeter can
      ;; return multiple nodes. We need some state tracking mechanism to cover
      ;; that case...
      (util/remove-node (some #(if (not= % n) %) (:nodes t)) n)
      (swap! (:membership t) assoc n :removed)
      :removed)
    (fn stop [t n]
      ;; FIXME: Same multi-node disruption issue applies here
      (util/add-node (some #(if (not= % n) %) (:nodes t)) n)
      (swap! (:membership t) assoc n :ok)
      :added))))
