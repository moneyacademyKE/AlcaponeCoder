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

(defn- get-stats-file []
  (io/file (io/file (System/getProperty "user.home") ".hermes" "skills") "stats.json"))

(defn save-system-state! [system]
  (let [cron-jobs (get system :cron-jobs {})
        skill-stats (get system :skill-stats {})]
    (spit "jobs.json" (json/generate-string (vals cron-jobs) {:pretty true}))
    (spit (get-stats-file) (json/generate-string skill-stats))))

(defn update-skill-stats! [skill-name success?]
  (let [f (get-stats-file)
        stats (if (.exists f) (json/parse-string (slurp f) true) {})
        current (get stats (keyword skill-name) {:hits 0 :successes 0})
        new-entry (-> current
                      (update :hits inc)
                      (update :successes (fn [s] (if success? (inc s) s))))
        new-stats (assoc stats (keyword skill-name) new-entry)]
    (spit f (json/generate-string new-stats))))

(defn- get-session-dir []
  (let [dir (io/file (System/getProperty "user.home") ".hermes" "sessions")]
    (.mkdirs dir)
    dir))

(defn save-checkpoint! [system]
  (let [id (get system :id "unknown")
        f (io/file (get-session-dir) (str id ".json"))
        ;; Exclude atoms and huge internal caches
        serializable (dissoc system :state :browser-process :registry)]
    (spit f (json/generate-string serializable {:pretty true}))))

(defn load-checkpoint [id]
  (let [f (io/file (get-session-dir) (str id ".json"))]
    (if (.exists f)
      (json/parse-string (slurp f) true)
      nil)))
