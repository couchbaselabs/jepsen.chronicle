(ns chronicle.util
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clojure.tools.logging :refer [info warn error fatal]]
            [clj-http.client :as http]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
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
   :--hostname c/*host*))

(defn setup-node
  [test]
  (info "Setting up node" c/*host*)
  (when (:install test)
    (c/upload (:install test) "/tmp/chronicle.tar")
    (cu/install-archive! "file:///tmp/chronicle.tar" "/home/vagrant/chronicle")
    (info "Building chronicle on" c/*host*)
    (c/cd "/home/vagrant/chronicle" (c/exec :rebar3 :as :examples :compile)))
  (info "Starting daemon on" c/*host*)
  (start-daemon)
  (Thread/sleep 5000))

(defn teardown-node
  []
  (info "Tearing down node" c/*host*)
  (cu/stop-daemon! "start_cluster" "/tmp/chronicle.pid")
  ;; Use ssh* since we want to ignore non-zero exit codes here
  (c/ssh* {:cmd "pkill -9 -f start_cluster"})
  (c/ssh* {:cmd "pkill -9 -f erlang"})
  (c/exec :rm :-rf "/tmp/chronicle.log")
  (c/exec :rm :-rf "/home/vagrant/chronicle/cluster"))

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

(defn get-nodes-with-status
  [test node-status]
  (->> @(:membership test)
       (group-by val)
       (node-status)
       (map first)))

(defn get-node-with-status
  [test node-status]
  (rand-nth (get-nodes-with-status test node-status)))

(defn get-one-ok-node
  [test]
  (get-node-with-status test :ok))
