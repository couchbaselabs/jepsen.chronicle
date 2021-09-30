(ns chronicle.client
  (:require [chronicle.util :as util]
            [clj-http.conn-mgr :as clj-http.conn-mgr]
            [jepsen
             [client :as client]
             [independent :as indep]]
            [slingshot.slingshot :refer [try+]]))

(defn read-op-worker [cm node op consistency]
  (try+
   (let [key (->> op :value first)
         val (util/key-get cm node key consistency)]
     (assoc op :type :ok :node node :value (indep/tuple key val)))
   ;; Read ops are idempotent so we can safely fail on any exception, but
   ;; we still want to parse exceptions to avoid cluttering the test log
   (catch java.net.ConnectException e
     (assoc op :type :fail :node node :error (.getMessage e) :exception e))
   (catch org.apache.http.NoHttpResponseException e
     (assoc op :type :fail :node node :error :NoHttpResponse :exception e))
   (catch [:status 400] e
     (assoc op :type :fail :node node :error :HTTP400 :exception e))
   (catch [:status 500] e
     (assoc op :type :fail :node node :error :HTTP500 :exception e))
   (catch Exception e
     (assoc op :type :fail :node node :error e))))

(defn do-read-op [cm node op consistency]
  (let [req (future (read-op-worker cm node op consistency))
        timeout? (= :timeout (deref req 3000 :timeout))]
    (if timeout?
      (do
        (future-cancel req)
        (assoc op :type :fail :node node :error :Timeout))
      @req)))

(defmacro try-write [node op & body]
  `(try+
    ~@body
    ;; If we never even connected the op definitely didn't logically happen
    (catch java.net.ConnectException e#
      (assoc ~op :type :fail :node ~node :error (.getMessage e#) :exception e#))
    ;; Indeterminate if we simply didn't get a response
    (catch org.apache.http.NoHttpResponseException e#
      (assoc ~op :type :info :node ~node :error :NoHttpResponse :exception e#))
    ;; HTTP/400 responses mean the op definitely didn't take place
    (catch [:status 400] e#
      (assoc ~op :type :fail :node ~node :error :HTTP400 :exception e#))
    ;; Transactions produce 409 when the transaction couldn't by applied after
    ;; the specified number of retries. The operation definitely didn't take
    ;; place.
    (catch [:status 409] e#
      (assoc ~op :type :fail :node ~node :error :HTTP409 :exception e#))
    ;; HTTP/500 responses indicate an ambiguous operation
    (catch [:status 500] e#
      (assoc ~op :type :info :node ~node :error :HTTP500 :exception e#))))

(defn write-op-worker [cm node op]
  (let [key (->> op :value first)
        val (->> op :value second)]
    (try-write node op
               (case (:f-type op)
                 :put (util/key-put cm node key val)
                 :post (util/key-post cm node key val))
               (assoc op :type :ok :node node))))

(defn do-write-op [cm node op]
  (let [req (future (write-op-worker cm node op))
        timeout? (= :timeout (deref req 3000 :timeout))]
    (if timeout?
      (do
        (future-cancel req)
        (assoc op :type :info :node node :error :Timeout))
      @req)))

(defn txn-op-worker [cm node op retries consistency]
  (try-write node op
             (let [snapshot (util/txn cm node (:value op) retries consistency)
                   ret (mapv (fn do-txn-subop [subop]
                               (case (first subop)
                                 :read (let [key (get subop 1)
                                             val (get snapshot key)]
                                         [:read key val])
                                 :write (let [key (get subop 1)
                                              val (get subop 2)]
                                          [:write key val])))
                             (:value op))]
               (assoc op :type :ok :node node :value ret))))

(defn do-txn-op [cm node op retries consistency]
  (let [req (future (txn-op-worker cm node op retries consistency))
        timeout? (= :timeout (deref req 3000 :timeout))]
    (if timeout?
      (do
        (future-cancel req)
        (assoc op :type :info :node node :error :Timeout))
      @req)))

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
        :txn (do-txn-op conn-manager node op
                        (:txn-retries test) (:consistency test))
        :read (do-read-op conn-manager node op (:consistency test))
        :write (do-write-op conn-manager node op))))
  (close! [_ _]
    (clj-http.conn-mgr/shutdown-manager conn-manager))
  (teardown! [_ _]))

(defn base-client []
  (chronicle-client. nil nil))
