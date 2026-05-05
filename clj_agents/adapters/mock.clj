(ns adapters.mock
  (:require [gateway]
            [adapters.base :as base])
  (:import [java.util.concurrent LinkedBlockingQueue]))

(defrecord MockAdapter [platform inbox dedup]
  gateway/Adapter
  (start [this callback-fn]
    (future
      (loop []
        (let [msg (.take inbox)]
          (let [msg-id (or (:message-id msg) (str (java.util.UUID/randomUUID)))]
            (when-not (base/duplicate? dedup msg-id)
              (let [event (gateway/map->MessageEvent
                           {:message-id msg-id
                            :text (:text msg)
                            :source (gateway/map->SessionSource
                                     {:platform platform
                                      :chat-id (:chat-id msg)
                                      :chat-type (:chat-type msg)
                                      :user-id (:user-id msg)})
                            :message-type "text"})
                    session-key (gateway/build-session-key (:source event))]
                (base/enqueue-batch! session-key (:text msg) event callback-fn))))
          (recur)))))
  (stop [this]
    ;; No-op for mock
    )
  (send-message [this chat-id content]
    (println (format "\n[%s OUT] To %s: %s" platform chat-id content))))

(defn create-mock-adapter [platform]
  (->MockAdapter platform (LinkedBlockingQueue.) (base/create-deduplicator 1000)))

(defn push-message! [adapter msg]
  (.put (:inbox adapter) msg))
