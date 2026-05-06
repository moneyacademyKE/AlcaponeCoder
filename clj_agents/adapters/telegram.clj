(ns adapters.telegram
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [adapters.base :as base]))

(defn- translate [update bot-token]
  (let [msg (:message update)
        from (:from msg)
        chat (:chat msg)]
    {:message_id (str (:message_id msg))
     :text (:text msg)
     :source {:platform "telegram"
              :chat_id (str (:id chat))
              :chat_type (if (= "private" (:type chat)) "dm" "group")
              :user_id (str (:id from))}
     :message_type "text"}))

(defn- get-updates [bot-token offset]
  (try
    (let [url (format "https://api.telegram.org/bot%s/getUpdates" bot-token)
          res (http/get url {:query-params {:offset offset :timeout 30} :throw false})]
      (if (= 200 (:status res))
        (get-in (json/parse-string (:body res) true) [:result])
        []))
    (catch Exception e (println "Telegram Error:" (ex-message e)) [])))

(defn- send-message [bot-token chat-id text]
  (try
    (let [url (format "https://api.telegram.org/bot%s/sendMessage" bot-token)]
      (http/post url {:body (json/generate-string {:chat_id chat-id :text text :parse_mode "Markdown"})
                      :headers {"Content-Type" "application/json"}}))
    (catch Exception e (println "Telegram Send Error:" (ex-message e)))))

(defn run-polling! [bot-token on-message-fn]
  (println "[TELEGRAM] Starting long polling...")
  (loop [offset 0]
    (let [updates (get-updates bot-token offset)]
      (doseq [update updates]
        (let [event (translate update bot-token)]
          (on-message-fn event)))
      (let [next-offset (if (seq updates) (inc (:update_id (last updates))) offset)]
        (recur next-offset)))))

(defn create-adapter [config on-message-fn]
  (let [token (:bot_token config)]
    {:send (fn [chat-id text] (send-message token chat-id text))
     :start (fn [] (future (run-polling! token on-message-fn)))}))
