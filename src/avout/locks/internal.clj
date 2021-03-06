(ns avout.locks.internal
  (:require [zookeeper :as zk]
            [zookeeper.util :as zutil]
            [zookeeper.logger :as log])
  (:import (java.util.concurrent.locks ReentrantLock)))


(defn unlock-all
  "Calls unlock on the given lock for each hold reported by getHoldCount"
  ([lock]
     (dotimes [i (.getHoldCount lock)] (.unlock lock))))

(defn find-next-lowest-node
  ([node sorted-nodes]
     (if (= node (first sorted-nodes))
       node
       (loop [[current & remaining] sorted-nodes
              previous nil]
         (when current
           (if (= node current)
             previous
             (recur remaining current)))))))

(defn create-lock-request-node
  ([client-handle lock-node request-id]
     (let [path (str lock-node "/" request-id "-")
           request-node (zk/create-all (.getClient client-handle) path :sequential? true)]
       (subs request-node (inc (count lock-node))))))

(defn delete-lock-request-node
  ([client-handle lock-node request-id]
   (let [client (.getClient client-handle)]
     (when-let [request-node (first (zk/filter-children-by-prefix client lock-node request-id))]
       (zk/delete client (str lock-node "/" request-node))))))

(defn delete-lock
  ([client-handle lock-node]
     (zk/delete-all (.getClient client-handle) lock-node)))

(defn queued-requests
  ([client-handle lock-node]
     (zk/children (.getClient client-handle) lock-node)))

(defn get-owner
  ([client-handle lock-node]
     (when-let [requests (zk/children (.getClient client-handle) lock-node)]
       (first (zutil/sort-sequential-nodes requests)))))

(defn submit-lock-request
  ([client-handle lock-node request-id lock-watcher]
     (let [monitor-lock (ReentrantLock. true)
           watched-node-event (.newCondition monitor-lock)
           client (.getClient client-handle)
           lock-request-node (create-lock-request-node client-handle lock-node request-id)
           watcher (fn [event] (try (.lock  monitor-lock) (.signal watched-node-event) (finally (.unlock monitor-lock))))]
       (future
         (try (.lock monitor-lock)
           (loop [lock-request-queue (zutil/sort-sequential-nodes (zk/children client lock-node))]
             (when (seq lock-request-queue) ;; if no requests in the queue, then exit
               ;; when the node-to-watch is nil, the requester is no longer in the queue, then exit
               (if-let [node-to-watch (find-next-lowest-node lock-request-node lock-request-queue)]
                 (if (zk/exists client (str lock-node "/" node-to-watch) :watcher watcher)
                   (do (when (= lock-request-node node-to-watch)
                         (lock-watcher)) ;; then lock-request-node is the new lock holder, invoke lock-watcher
                       (.await watched-node-event) ;; wait until zookeeper invokes the watcher, which calls notify
                       (recur (zutil/sort-sequential-nodes (zk/children client lock-node))))
                   ;; if the node-to-watch no longer exists recur to find the next node-to-watch
                   (recur (zutil/sort-sequential-nodes (zk/children client lock-node)))))))
           (finally (.unlock monitor-lock))))
       lock-request-node)))

;; READ LOCK FUNCTIONS

(defn submit-read-lock-request
  ([client-handle lock-node request-id lock-watcher]
     (let [monitor-lock (ReentrantLock. true)
           watched-node-event (.newCondition monitor-lock)
           client (.getClient client-handle)
           lock-request-node (create-lock-request-node client-handle lock-node request-id)
           watcher (fn [event] (try (.lock  monitor-lock) (.signal watched-node-event) (finally (.unlock monitor-lock))))]
       (future
         (try (.lock monitor-lock)
           (loop [lock-request-queue (zutil/sort-sequential-nodes (zk/children client lock-node))]
             (when (seq lock-request-queue) ;; if no requests in the queue, then exit
               ;; when the node-to-watch is nil, the requester is no longer in the queue, then exit
               (if-let [node-to-watch (or (seq (find-next-lowest-node lock-request-node (zutil/filter-nodes-by-prefix "write-" lock-request-queue)))
                                          lock-request-node)]
                 (if (zk/exists client (str lock-node "/" node-to-watch) :watcher watcher)
                   (do (when (= lock-request-node node-to-watch)
                         (lock-watcher)) ;; then lock-request-node is the new lock holder, invoke lock-watcher
                       (.await watched-node-event) ;; wait until zookeeper invokes the watcher, which calls notify
                       (recur (zutil/sort-sequential-nodes (zk/children client lock-node))))
                   ;; if the node-to-watch no longer exists recur to find the next node-to-watch
                   (recur (zutil/sort-sequential-nodes (zk/children client lock-node)))))))
           (finally (.unlock monitor-lock))))
       lock-request-node)))



