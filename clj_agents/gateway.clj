(ns gateway
  (:require [agent]
            [registry]
            [store]
            [clojure.string :as str]))

(defrecord SessionSource [platform chat-id chat-type user-id])
(defrecord MessageEvent [message-id text source message-type])

(defprotocol Adapter
  (start [this callback-fn])
  (stop [this])
  (send-message [this chat-id content]))

(defn build-session-key [src]
  (let [{:keys [platform chat-id chat-type user-id]} src]
    (if (= chat-type "dm")
      (format "agent:main:%s:dm:%s" platform chat-id)
      (format "agent:main:%s:group:%s:%s" platform chat-id user-id))))

(defonce active-sessions (atom {})) ;; session-key -> interrupt-flag

(defn handle-message [runner event]
  (let [session-key (build-session-key (:source event))
        agent-state (get-in @runner [:agents session-key] (atom {:cached-prompt nil}))]
    ;; Update runner with agent state
    (swap! runner assoc-in [:agents session-key] agent-state)
    
    ;; In a real gateway, we'd check active-sessions and buffer/interrupt here.
    ;; For this simulation, we'll process sequentially.
    (let [budget (atom 90)
          config (:config @runner)]
      (binding [registry/*session-id* session-key
                registry/*budget* budget
                registry/*config* config
                registry/*depth* 1]
        (let [result (agent/run-conversation session-key (:text event) agent-state)]
          (:final-response result))))))

(defn create-gateway-runner [config]
  (atom {:config config
         :adapters {}
         :agents {}}))

(defn register-adapter! [runner platform adapter]
  (swap! runner assoc-in [:adapters platform] adapter)
  (start adapter (fn [event] (handle-message runner event))))
