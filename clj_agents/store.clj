(ns store
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(def db-path "state.json")

(defn load-db []
  (if (.exists (io/file db-path))
    (json/parse-string (slurp db-path) true)
    {:sessions {} :messages {}}))

(defn save-db [db]
  (spit db-path (json/generate-string db)))

(defn init-db! []
  (save-db (load-db)))

(defn create-session! [id source]
  (let [db (load-db)
        k (keyword id)]
    (save-db (assoc-in db [:sessions k] {:source source :started_at (/ (System/currentTimeMillis) 1000.0)}))))

(defn add-messages! [session-id messages]
  (let [db (load-db)
        k (keyword session-id)
        existing (get-in db [:messages k] [])]
    (save-db (assoc-in db [:messages k] (into existing messages)))))

(defn get-session-messages [session-id]
  (let [db (load-db)]
    (get-in db [:messages (keyword session-id)] [])))
