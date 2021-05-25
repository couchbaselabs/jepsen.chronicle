(ns chronicle.nemesis
  (:require [chronicle.util :as util]
            [clojure.set :as set]
            [clojure.tools.logging :refer [info warn error fatal]]
            [jepsen
             [control :as c]
             [nemesis :as nemesis]
             [net :as net]]
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
                (assoc op :call-node call-node))
        :failover (let [all-nodes (set (:nodes test))
                        remove-nodes (set (:value op))
                        keep-nodes (set/difference all-nodes remove-nodes)
                        call-node (rand-nth (seq keep-nodes))]
                    (util/failover-nodes call-node keep-nodes)
                    (update-states test (:value op) :failed-over)
                    (assoc op :call-node call-node))))
    (teardown! [this test])))

(defn disk-nemesis
  []
  (reify nemesis/Nemesis
    (setup! [this test]
      (if-not (:requires-vdisk test)
        (throw (RuntimeException.
                (str "Workloads using the disk nemesis must declare "
                     ":requires-vdisk in the test map"))))
      this)
    (invoke! [this test op]
      (case (:f op)
        :fail-disk
        (do (c/on-many (:value op)
                       (c/su
                        (c/exec :dmsetup :wipe_table :vdisk :--noflush :--nolockfs)))
            (update-states test (:value op) :disk-failure)
            op)

        :drop-cache
        (do (c/on-many (:value op)
                       (c/su
                        (c/exec :echo "3" :> "/proc/sys/vm/drop_caches")))
            op)

        :recover-disk
        (do (c/on-many (:value op)
                       (info "Stopping node")
                       (util/stop-node)
                       (c/su
                        (info "Unmounting")
                        (c/exec :umount "/dev/mapper/vdisk")
                        (info "Setting new table")
                        (c/exec :dmsetup :load :vdisk :--table
                                (c/lit (str "'0 2097152 linear "
                                            (util/get-vdisk-loop-device)
                                            " 0'")))
                        (info "Resuming disk")
                        (c/exec :dmsetup :resume :vdisk)
                        (info "Remounting disk")
                        (c/exec :mount "/dev/mapper/vdisk"
                                "/home/vagrant/chronicle/cluster"))
                       (info "Restarting daemon")
                       (util/start-daemon))
            (update-states test (:value op) :ok)
            op)))
    (teardown! [this test]
      ;; Attempt to recover the any failed disks so that we can get logs
      (let [damaged (util/get-nodes-with-status test :disk-failure)]
        (when-not (empty? damaged)
          (info "Attempting disk recovery on nodes" damaged)
          (nemesis/invoke! this test {:f :recover-disk :value damaged}))))))

(defn network-partition
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :isolate-completely
        (let [isolate-nodes (:value op)
              other-nodes (set/difference (set (:nodes test))
                                          (set isolate-nodes))
              partitions (conj (partition 1 isolate-nodes) other-nodes)
              grudge (nemesis/complete-grudge partitions)]
          (net/drop-all! test grudge)
          (update-states test (:value op) :partitioned)
          op)

        :heal-network
        (do
          (net/heal! (:net test) test)
          ;; This potentialy interferes with other disruptions, but we need to
          ;; rework the membership atom to fix this
          (update-states test (:nodes test) :ok)
          op)))
    (teardown! [this test]
      ;; Heal network during teardown to avoid leaving test nodes broken
      (net/heal! (:net test) test))))
