(ns chronicle.client
  (:require [chronicle.util :as util]
            [clj-http.conn-mgr :as clj-http.conn-mgr]
            [jepsen
             [client :as client]
             [independent :as indep]]
            [slingshot.slingshot :refer [try+]]))

(defn do-read-op [cm node op consistency]
  (try+
    (let [key (->> op :value first)
          val (util/key-get cm node key consistency)
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

(defn do-write-op [cm node op]
  (let [key (->> op :value first)
        val (->> op :value second)]
    (try+
     (case (:f-type op)
       :put (util/key-put cm node key val)
       :post (util/key-post cm node key val))
     (assoc op :type :ok :node node)
     ;; HTTP/400 responses mean the op definitely didn't take place
     (catch [:status 400] e
       (assoc op :type :fail :node node :error :HTTP400 :exception e))
     ;; HTTP/500 responses indicate an ambiguous operation
     (catch [:status 500] e
       (assoc op :type :info :node node :error :HTTP500 :exception e)))))

(defrecord chronicle-client [client-node conn-manager]
  client/Client
  (open! [this test node]
    (let [cm (clj-http.conn-mgr/make-reusable-conn-manager nil)]
      (assoc this :client-node node :conn-manager cm)))
  (setup! [_ _])
  (invoke! [_ test op]
    (let [node (case (:client-stickiness test)
                 :sticky client-node
                 :any-node (rand-nth (:nodes test))
                 :healthy-nodes (util/get-one-ok-node test))]
      (case (:f op)
        :read (do-read-op conn-manager node op (:consistency test))
        :write (do-write-op conn-manager node op))))
  (close! [_ _]
    (clj-http.conn-mgr/shutdown-manager conn-manager))
  (teardown! [_ _]))

(defn base-client []
  (chronicle-client. nil nil))
