(ns chronicle.util
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clojure.tools.logging :refer [info warn error fatal]]
            [clj-http.client :as http]
            [dom-top.core :refer [with-retry]]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.util :as ju]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import java.io.File))

(defn get-install-package
  [package]
  (assert (.isDirectory (io/file package)))
  (let [temp-file (File/createTempFile "chronicle" ".tar")]
    (shell/sh "tar" "-cf" (.getCanonicalPath ^File temp-file) package)
    (.deleteOnExit temp-file)
    (.getCanonicalPath ^File temp-file)))

(defn start-daemon
  []
  (cu/start-daemon!
   {:logfile "/tmp/chronicle.log"
    :pidfile "/tmp/chronicle.pid"
    :chdir "/home/vagrant/chronicle"}
   "start_cluster"
   :--app :chronicled
   :--num-nodes 1
   :--hostname c/*host*)
  (cu/await-tcp-port 8080))

(defn stop-node
  []
  (ju/meh (c/exec :pkill :-P (c/lit "$(</tmp/chronicle.pid)")))
  ;; Wait 1s after the above SIGTERM before forcibly SIGKILL any remaining
  ;; processes
  (Thread/sleep 1000)
  (c/ssh* {:cmd "pkill -9 -f start_cluster"})
  (c/ssh* {:cmd "pkill -9 -f erlang"})
  (c/exec :rm :-f "/tmp/chronicle.pid"))

(defn get-vdisk-loop-device
  []
  (c/su (c/exec :losetup :-l "|" :grep :-e "chronicle-disk" "|" :cut :-f "1" :-d (c/lit "' '"))))

(defn setup-node
  [test]
  (info "Setting up node" c/*host*)
  (when (:install test)
    (c/upload (:install test) "/tmp/chronicle.tar")
    (cu/install-archive! "file:///tmp/chronicle.tar" "/home/vagrant/chronicle")
    (info "Building chronicle on" c/*host*)
    (c/cd "/home/vagrant/chronicle"
          (with-retry [retries 5]
            (c/exec "DEBUG=1" :rebar3 :as :examples :compile)
            (catch Exception e
              (warn "Error occurred during compilation"
                    retries
                    "attempts remaining. Exception was"
                    e)
              (if (> retries 0)
                (retry (dec retries))
                (throw e))))))
  (when (:requires-vdisk test)
    (info "Preparing virtual disk")
    (c/su
     (c/exec :fallocate :-l "1G" "/tmp/chronicle-disk.img")
     (let [loop-device (c/exec :losetup :-f :--show "/tmp/chronicle-disk.img")]
       (c/exec :dmsetup :create :vdisk :--table
               (c/lit (str "'0 2097152 linear " loop-device " 0'"))))
     (c/exec :mkfs.xfs "/dev/mapper/vdisk")
     (c/exec :mkdir :-p "/home/vagrant/chronicle/cluster")
     (c/exec :mount "/dev/mapper/vdisk" "/home/vagrant/chronicle/cluster")
     (c/exec :chown :vagrant "/home/vagrant/chronicle/cluster")))
  (info "Starting daemon on" c/*host*)
  (start-daemon))

(defn teardown-node
  []
  (info "Tearing down node" c/*host*)
  (cu/stop-daemon! "start_cluster" "/tmp/chronicle.pid")
  ;; Use ssh* since we want to ignore non-zero exit codes here
  (c/ssh* {:cmd "pkill -9 -f start_cluster"})
  (c/ssh* {:cmd "pkill -9 -f erlang"})
  (c/exec :rm :-f "/tmp/chronicle.log")
  (c/su
   (ju/meh (c/exec :umount "/dev/mapper/vdisk"))
   (ju/meh (c/exec :dmsetup :remove :vdisk))
   (ju/meh (c/exec :losetup :-d (get-vdisk-loop-device)))
   (c/exec :rm :-f "/tmp/chronicle-disk.img")
   (c/exec :rm :-rf "/home/vagrant/chronicle/cluster")))

(defn format-nodes
  [nodes]
  (if (coll? nodes)
    (->> nodes
         (map #(str "\"chronicle_0@" % "\""))
         (string/join ",")
         (format "[%s]"))
    (str "\"chronicle_0@" nodes "\"")))

(defn add-nodes
  [rest-target nodes]
  (http/post (str "http://" rest-target ":8080/config/addnode")
             {:body (format-nodes nodes)
              :content-type :json
              :throw-entire-message? true}))

(defn remove-nodes
  [rest-target nodes]
  (http/post (str "http://" rest-target ":8080/config/removenode")
             {:body (format-nodes nodes)
              :content-type :json
              :throw-entire-message? true}))

(defn failover-nodes
  [rest-target keep-nodes]
  (http/post (str "http://" rest-target ":8080/config/failover")
             {:body (format-nodes keep-nodes)
              :content-type :json
              :throw-entire-message? true}))

(defn wipe-node
  [node]
  (http/post (str "http://" node ":8080/node/wipe")
             {:throw-entire-message? true}))

(defn setup-cluster
  [test primary_node]
  (http/get (str "http://" primary_node ":8080/config/provision"))
  (->> (:nodes test)
       (remove #(= primary_node %))
       (add-nodes primary_node)))

(defn key-put
  [cm node key value]
  (http/put (format "http://%s:8080/kv/key%s" node key)
            {:body (str value)
             :content-type :json
             :connection-manager cm}))

(defn key-post
  [cm node key value]
  (http/post (format "http://%s:8080/kv/key%s" node key)
             {:body (str value)
              :content-type :json
              :connection-manager cm}))

(defn key-get
  [cm node key consistency]
  (try+
   (-> (format "http://%s:8080/kv/key%s" node key)
       (http/get {:connection-manager cm
                  :query-params {:consistency consistency}})
       (:body)
       (json/parse-string true)
       (:value))
   ;; Return :KeyNotFound if the key doesn't exist
   (catch [:status 404] _ :KeyNotFound)))

(defn txn
  [cm node ops retries consistency]
  (let [ops-object (mapv
                    (fn [op]
                      (case (first op)
                        :read {:op :get
                               :key (str (get op 1))}
                        :write {:op :set
                                :key (str (get op 1))
                                :value (get op 2)}))
                    ops)
        reply (-> (format "http://%s:8080/kv" node)
                  (http/post
                   {:body (json/generate-string ops-object)
                    :content-type :json
                    :connection-manager cm
                    :query-params {:retries retries
                                   :consistency consistency}})
                  :body
                  json/parse-string)]
    (apply hash-map
           (mapcat (fn [op]
                     (case (first op)
                       :read (let [key (get op 1)
                                   payload (get reply (str key))
                                   value (if payload
                                           (get payload "value")
                                           :KeyNotFound)]
                               [key value])
                       :write []))
                   ops))))

(defn get-nodes-with-status
  [test select-status]
  (keep (fn [[node status]] (if (contains? status select-status) node))
        @(:membership test)))

(defn get-ok-nodes
  [test]
  (->> @(:membership test)
       (keep (fn [[k v]] (if (empty? v) k)))))

(defn get-one-ok-node
  [test]
  (->> @(:membership test)
       (keep (fn [[k v]] (if (empty? v) k)))
       (rand-nth)))
