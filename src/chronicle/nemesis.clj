(ns chronicle.nemesis
  (:require [chronicle.util :as util]
            [jepsen
             [control :as c]
             [nemesis :as nemesis]]
            [jepsen.control.util :as cu]))

(defn- update-states
  [test nodes state]
  (let [kvpair (interleave nodes (repeat state))]
    (apply swap! (:membership test) assoc kvpair)))

(defn node-freeze
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :freeze (do
                  (c/on-many
                   (:value op)
                   (c/exec :pkill :-SIGSTOP :beam.smp
                           :-P (c/lit "$(< /tmp/chronicle.pid)")))
                  (update-states test (:value op) :frozen)
                  op)
        :resume (do
                  (c/on-many
                   (:value op)
                   (c/exec :pkill :-SIGCONT :beam.smp
                           :-P (c/lit "$(< /tmp/chronicle.pid)")))
                  (update-states test (:value op) :ok)
                  op)))
    (teardown! [this test])))

(defn node-crash
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :crash (do
                 (c/on-many
                  (:value op)
                  (c/exec :pkill :-SIGKILL
                          :-P (c/lit "$(< /tmp/chronicle.pid)"))
                  (cu/stop-daemon! "/tmp/chonicle.pid"))
                 (update-states test (:value op) :killed)
                 op)
        :restart (do
                   (c/on-many (:value op) (util/start-daemon))
                   (update-states test (:value op) :ok)
                   op)))
    (teardown! [this test])))

(defn node-removal
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :remove (let [call-node (util/get-one-ok-node test)]
                  (util/remove-nodes call-node (:value op))
                  (update-states test (:value op) :removed)
                  (assoc op :call-node call-node))
        :wipe (do
                (doseq [node (:value op)]
                  (util/wipe-node node))
                (update-states test (:value op) :wiped)
                op)
        :join (let [call-node (util/get-one-ok-node test)]
                (util/add-nodes call-node (:value op))
                (update-states test (:value op) :ok)
                (assoc op :call-node call-node))))
    (teardown! [this test])))
