(ns chronicle.util
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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
    (shell/sh "tar" "-cf" (.getCanonicalPath ^File temp-file) "./chronicle")
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

(defn add-node
  [rest-target node]
  (http/post (str "http://" rest-target ":8080/config/addnode")
             {:body (str "\"chronicle_0@" node "\"")
              :content-type :json
              :throw-entire-message? true}))

(defn remove-node
  [rest-target node]
  (http/post (str "http://" rest-target ":8080/config/removenode")
             {:body (str "\"chronicle_0@" node "\"")
              :content-type :json
              :throw-entire-message? true})
  (http/post (str "http://" node ":8080/node/wipe")
             {:throw-entire-message? true}))

(defn setup-cluster
  [test primary_node]
  (http/get (str "http://" primary_node ":8080/config/provision"))
  (doseq [node (:nodes test)]
    (if (not= node primary_node)
      (add-node primary_node node))))

(defn key-put
  [node key value]
  (http/put (format "http://%s:8080/kv/key%s" node key)
            {:body (str value)
             :content-type :json}))

(defn key-post
  [node key value]
  (http/post (format "http://%s:8080/kv/key%s" node key)
             {:body (str value)
              :content-type :json}))

(defn key-get
  [node key consistency]
  (try+
   (-> (format "http://%s:8080/kv/key%s" node key)
       (http/get {:query-params {:consistency consistency}})
       (:body)
       (json/parse-string true)
       (:value))
   ;; Return :KeyNotFound if the key doesn't exist
   (catch [:status 404] _ :KeyNotFound)))
