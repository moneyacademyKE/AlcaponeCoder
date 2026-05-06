(ns gateway
  (:require [agent]
            [registry]
            [store]
            [backend]
            [clojure.string :as str]
            [adapters.telegram :as telegram]))

(defn build-session-key [src]
  (let [{:keys [platform chat-id chat-type user-id]} src]
    (if (= chat-type "dm")
      (format "agent:main:%s:dm:%s" platform chat-id)
      (format "agent:main:%s:group:%s:%s" platform chat-id user-id))))

(defonce active-sessions (atom {})) ;; session-key -> future

(defn handle-message [runner event]
  (let [session-key (build-session-key (:source event))
        platform (get-in event [:source :platform])
        adapter (get-in @runner [:adapters platform])
        agent-state (get-in @runner [:agents session-key] (atom {:cached-prompt nil}))
        config (:config @runner)
        ;; Create a system map for this conversation turn
        system {:id session-key
                :config config
                :budget (atom 90)
                :depth 0
                :env (backend/create-env :local)
                :browser-process (atom nil)}]
    
    (swap! runner assoc-in [:agents session-key] agent-state)
    
    (future
      (try
        (let [result (agent/run-conversation system (:text event) agent-state)]
          (when adapter
            ((:send adapter) (get-in event [:source :chat_id]) (:final-response result))))
        (finally
          ;; Cleanup Browser Daemon
          (when-let [p @(:browser-process system)]
            (try
              (.write (clojure.java.io/writer (:in p)) "{\"action\": \"exit\"}\n")
              (.flush (clojure.java.io/writer (:in p)))
              (catch Exception _ (.destroy p))))
          (backend/cleanup (:env system)))))))

(defn create-gateway-runner [config]
  (atom {:config config
         :adapters {}
         :agents {}}))

(defn start-gateway! [runner]
  (let [config (:config @runner)]
    ;; Register Signal Handlers for Graceful Shutdown
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (println "\n[SYSTEM] Shutting down gateway...")
                                 (stop-gateway! runner))))
    
    ;; Start Telegram if configured
    (when-let [tg-cfg (get-in config [:platforms :telegram])]
      (when (:enabled tg-cfg)
        (let [adapter (telegram/create-adapter tg-cfg (fn [event] (handle-message runner event)))]
          (swap! runner assoc-in [:adapters "telegram"] adapter)
          ((:start adapter)))))))

(defn stop-gateway! [runner]
  (doseq [[p adapter] (:adapters @runner)]
    (when-let [stop-fn (:stop adapter)]
      (stop-fn))))
