(ns chronicle.client
  (:require [chronicle.util :as util]
            [jepsen
             [client :as client]
             [independent :as indep]]
            [slingshot.slingshot :refer [try+]]))

(defn do-read-op [node op consistency]
  (try+
    (let [key (->> op :value first)
          val (util/key-get node key consistency)
          ret (indep/tuple key val)]
      (assoc op :type :ok :value ret))
    ;; Read ops are idempotent so we can safely fail on any exception, but
    ;; we still want to parse reponse codes to avoid cluttering the test log
    (catch [:status 400] e
      (assoc op :type :fail :error :HTTP400 :exception e))
    (catch [:status 500] e
      (assoc op :type :fail :error :HTTP500 :exception e))
    (catch Exception e
      (assoc op :type :fail :error e))))

(defn do-write-op [node op]
  (let [key (->> op :value first)
        val (->> op :value second)]
    (try+
     (case (:f-type op)
       :put (util/key-put node key val)
       :post (util/key-post node key val))
     (assoc op :type :ok)
     ;; HTTP/400 responses mean the op definitely didn't take place
     (catch [:status 400] e
       (assoc op :type :fail :error :HTTP400 :exception e))
     ;; HTTP/500 responses indicate an ambiguous operation
     (catch [:status 500] e
       (assoc op :type :info :error :HTTP500 :exception e)))))

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
