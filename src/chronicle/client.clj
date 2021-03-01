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
      (assoc op :type :ok :node node :value ret))
    ;; Read ops are idempotent so we can safely fail on any exception, but
    ;; we still want to parse reponse codes to avoid cluttering the test log
    (catch [:status 400] e
      (assoc op :type :fail :node node :error :HTTP400 :exception e))
    (catch [:status 500] e
      (assoc op :type :fail :node node :error :HTTP500 :exception e))
    (catch Exception e
      (assoc op :type :fail :node node :error e))))

(defn do-write-op [node op]
  (let [key (->> op :value first)
        val (->> op :value second)]
    (try+
     (case (:f-type op)
       :put (util/key-put node key val)
       :post (util/key-post node key val))
     (assoc op :type :ok :node node)
     ;; HTTP/400 responses mean the op definitely didn't take place
     (catch [:status 400] e
       (assoc op :type :fail :node node :error :HTTP400 :exception e))
     ;; HTTP/500 responses indicate an ambiguous operation
     (catch [:status 500] e
       (assoc op :type :info :node node :error :HTTP500 :exception e)))))

(defrecord chronicle-client [client-node]
  client/Client
  (open! [this test node] (assoc this :client-node node))
  (setup! [_ _])
  (invoke! [_ test op]
    (let [node (case (:client-stickiness test)
                 :sticky client-node
                 :any-node (rand-nth (:nodes test))
                 :healthy-nodes (util/get-one-ok-node test))]
      (case (:f op)
        :read (do-read-op node op (:consistency test))
        :write (do-write-op node op))))
  (close! [_ _])
  (teardown! [_ _]))

(defn base-client []
  (chronicle-client. nil))
