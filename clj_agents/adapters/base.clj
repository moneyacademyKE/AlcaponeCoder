(ns adapters.base
  (:require [clojure.string :as str]))

;; --- Deduplication ---
(defn create-deduplicator [max-size]
  (atom {:seen #{} :order [] :max-size max-size}))

(defn duplicate? [dedup-atom msg-id]
  (if (contains? (:seen @dedup-atom) msg-id)
    true
    (do
      (swap! dedup-atom (fn [{:keys [seen order max-size]}]
                          (let [new-seen (conj seen msg-id)
                                new-order (conj order msg-id)]
                            (if (> (count new-order) max-size)
                              (let [old-id (first new-order)]
                                {:seen (disj new-seen old-id)
                                 :order (subvec new-order 1)
                                 :max-size max-size})
                              {:seen new-seen
                               :order new-order
                               :max-size max-size}))))
      false)))

;; --- Text Batching ---
(defonce batch-buffers (atom {})) ;; session-key -> {:fragments [] :timer-task nil :event nil}

(defn flush-batch! [session-key callback-fn]
  (let [{:keys [fragments event]} (get @batch-buffers session-key)]
    (swap! batch-buffers dissoc session-key)
    (when (seq fragments)
      (let [merged-text (str/join "" fragments)
            final-event (assoc event :text merged-text)]
        (callback-fn final-event)))))

(defn enqueue-batch! [session-key text event callback-fn]
  (let [existing (get @batch-buffers session-key)
        _ (when-let [old-timer (:timer-task existing)]
            (future-cancel old-timer))
        new-fragments (conj (or (:fragments existing) []) text)
        ;; Simple heuristic: if text is long, wait longer
        delay (if (>= (count text) 3900) 2000 600)
        new-timer (future
                    (Thread/sleep delay)
                    (flush-batch! session-key callback-fn))]
    (swap! batch-buffers assoc session-key
           {:fragments new-fragments
            :timer-task new-timer
            :event event})))
