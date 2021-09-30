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
  "Update the test membership status atom. For each node in nodes, update
  it's status set to include or remove the listed status keywords"
  [test nodes action-fn & states]
  (swap! (:membership test)
         (partial reduce #(apply update %1 %2 action-fn states))
         nodes))

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
                  (update-states test (:value op) conj :frozen)
                  op)
        :resume (do
                  (c/on-many
                   (:value op)
                   (c/exec :pkill :-SIGCONT :beam.smp
                           :-P (c/lit "$(< /tmp/chronicle.pid)")))
                  (update-states test (:value op) disj :frozen)
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
                 (update-states test (:value op) conj :killed)
                 op)
        :restart (do
                   (c/on-many (:value op) (util/start-daemon))
                   (update-states test (:value op) disj :killed :frozen)
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
                  (update-states test (:value op) conj :removed)
                  (assoc op :call-node call-node))
        :wipe (do
                (doseq [node (:value op)]
                  (util/wipe-node node))
                (update-states test (:value op) conj :wiped)
                op)
        :join (let [call-node (util/get-one-ok-node test)]
                (util/add-nodes call-node (:value op))
                (update-states test (:value op) disj :removed :wiped)
                (assoc op :call-node call-node))
        :failover (let [all-nodes (set (:nodes test))
                        remove-nodes (set (:value op))
                        keep-nodes (set/difference all-nodes remove-nodes)
                        call-node (rand-nth (seq keep-nodes))]
                    (util/failover-nodes call-node keep-nodes)
                    (update-states test (:value op) conj :removed)
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
        ;; Simulate a failed disk (return IO error on all requests)
        :fail-disk
        (do (c/on-many (:value op)
                       (c/su
                        (c/exec :dmsetup :wipe_table :vdisk :--noflush :--nolockfs)))
            (update-states test (:value op) conj :disk-failure)
            op)

        ;; Simulate a slow disk using the DM Delay taget, see documentation at
        ;; https://www.kernel.org/doc/html/v5.14/admin-guide/device-mapper/delay.html
        :slow-disk
        (do (c/on-many (:value op)
                       (c/su
                        (info "Starting slow disk")
                        (c/exec :dmsetup :suspend :vdisk :--noflush :--nolockfs)
                        (info "Disk suspended")
                        (c/exec :dmsetup :reload :vdisk :--table
                                ;; Delay IO by 50ms
                                (c/lit (str "'0 2097152 delay "
                                            (util/get-vdisk-loop-device)
                                            " 0 50'")))
                        (info "New table loaded")
                        (c/exec :dmsetup :resume :vdisk :--noflush :--nolockfs)
                        (info "Disk resumed")))
            (update-states test (:value op) conj :disk-failure)
            op)

        ;; Simulate a failing disk that (sometimes) misbehaves using the DM Flakey target,
        ;; see documentation at
        ;; https://www.kernel.org/doc/html/v5.14/admin-guide/device-mapper/dm-flakey.html
        :flakey-disk
        (do (c/on-many (:value op)
                       (c/su
                        (c/exec :dmsetup :suspend :vdisk :--noflush :--nolockfs)
                        (c/exec :dmsetup :reload :vdisk :--table
                                ;; Silently drop writes 50% of the time (1s up/down)
                                (c/lit (str "'0 2097152 flakey "
                                            (util/get-vdisk-loop-device)
                                            " 0 1 1 1 drop_writes'")))
                        (c/exec :dmsetup :resume :vdisk :--noflush :--nolockfs)))
            (update-states test (:value op) conj :disk-failure)
            op)

        ;; Drop caches
        :drop-cache
        (do (c/on-many (:value op)
                       (c/su
                        (c/exec :echo "3" :> "/proc/sys/vm/drop_caches")))
            op)

        ;; Restore a "failed" disk, requires restarting chronicle in order to
        ;; be able to remount the filesystem, which will otherwise likely be
        ;; stuck in read-only mode following IO errors
        :recover-disk
        (do (c/on-many (:value op)
                       (info "Stopping node")
                       (util/stop-node)
                       (c/su
                        (info "Unmounting")
                        (c/exec :umount "/dev/mapper/vdisk")
                        (info "Suspending vdisk")
                        (c/exec :dmsetup :suspend :vdisk)
                        (info "Setting new table")
                        (c/exec :dmsetup :load :vdisk :--table
                                (c/lit (str "'0 2097152 linear "
                                            (util/get-vdisk-loop-device)
                                            " 0'")))
                        (info "Resuming disk")
                        (c/exec :dmsetup :resume :vdisk)
                        (try
                          (info "Remounting disk")
                          (c/exec :mount "/dev/mapper/vdisk"
                                  "/home/vagrant/chronicle/cluster")
                          (catch Exception e
                            ;; If remounting fails assume this might be due to
                            ;; a damaged filesystem, and try running xfs_repair
                            ;; to force log zeroing. Running this blindly is
                            ;; generally a bad idea and will likely result in a
                            ;; corrupted filesystem, but that's sort of the
                            ;; point of the test
                            (info "Remount failed, attempting FS recovery")
                            (c/exec :xfs_repair :-L "/dev/mapper/vdisk")
                            (info "Repair done, remounting")
                            (c/exec :mount "/dev/mapper/vdisk"
                                    "/home/vagrant/chronicle/cluster"))))
                       (info "Restarting daemon")
                       (util/start-daemon))
            (update-states test (:value op) disj :disk-failure)
            op)))
    (teardown! [this test]
      ;; Attempt to recover the any failed disks so that we can get logs
      (let [damaged (util/get-nodes-with-status test :disk-failure)]
        (when-not (empty? damaged)
          (info "Attempting disk recovery on nodes" damaged)
          (try
            (nemesis/invoke! this test {:f :recover-disk :value damaged})
            (catch Exception e
              (error "Error attempting disk recovery:" e))))))))

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
          (update-states test (:value op) conj :partitioned)
          op)

        :heal-network
        (do
          (net/heal! (:net test) test)
          (update-states test (:nodes test) disj :partitioned)
          op)))
    (teardown! [this test]
      ;; Heal network during teardown to avoid leaving test nodes broken
      (net/heal! (:net test) test))))
