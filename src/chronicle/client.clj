(ns chronicle.client
  (:require [chronicle.util :as util]
            [jepsen
             [client :as client]
             [independent :as indep]]))

(defn do-read-op [node op consistency]
  (try
    (let [key (->> op :value first)
          val (util/key-get node key consistency)
          ret (indep/tuple key val)]
      (assoc op :type :ok :value ret))
    (catch Exception e
      (assoc op :type :fail :error e))))

(defn do-write-op [node op]
  (let [key (->> op :value first)
        val (->> op :value second)]
    (case (:f-type op)
      :put (util/key-put node key val)
      :post (util/key-post node key val))
    (assoc op :type :ok)))

(defrecord chronicle-client [client-node]
  client/Client
  (open! [this test node] (assoc this :client-node node))
  (setup! [_ _])
  (invoke! [_ test op]
    (case (:f op)
      :read (do-read-op client-node op (:consistency test))
      :write (do-write-op client-node op)))
  (close! [_ _])
  (teardown! [_ _]))

(defn base-client []
  (chronicle-client. nil))
