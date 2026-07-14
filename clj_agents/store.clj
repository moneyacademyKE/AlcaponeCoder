(ns store
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

(defn get-hermes-home []
  (let [env-home (System/getenv "HERMES_HOME")
        default-home (str (System/getProperty "user.home") "/.hermes")]
    (io/file (or env-home default-home))))

(defn get-db-path []
  (str (io/file (get-hermes-home) "state.db")))

(defn run-sql [sql]
  (let [db (get-db-path)
        res (shell {:out :string :err :string :continue true} "sqlite3" db sql)]
    (if (zero? (:exit res))
      (:out res)
      (throw (Exception. (str "SQL error: " (:err res) "\nQuery: " sql))))))

(defn run-sql-json [sql]
  (let [db (get-db-path)
        res (shell {:out :string :err :string :continue true} "sqlite3" "-json" db sql)]
    (if (zero? (:exit res))
      (let [out (str/trim (:out res))]
        (if (empty? out) [] (json/parse-string out true)))
      (throw (Exception. (str "SQL error: " (:err res) "\nQuery: " sql))))))

(defn escape-str [s]
  (if (string? s)
    (str/replace s "'" "''")
    s))

(defn init-db! []
  (.mkdirs (get-hermes-home))
  (run-sql "CREATE TABLE IF NOT EXISTS datoms (
              entity TEXT NOT NULL,
              attribute TEXT NOT NULL,
              value TEXT NOT NULL,
              tx INTEGER NOT NULL,
              PRIMARY KEY (entity, attribute, value, tx)
            );"))

(defn transact-datoms! [datoms]
  (init-db!)
  (let [tx (System/currentTimeMillis)
        statements (map (fn [[e a v]]
                          (format "INSERT OR REPLACE INTO datoms (entity, attribute, value, tx) VALUES ('%s', '%s', '%s', %d);"
                                  (escape-str (str e))
                                  (escape-str (str a))
                                  (escape-str (str v))
                                  tx))
                        datoms)
        batch-sql (str "BEGIN TRANSACTION;\n"
                       (str/join "\n" statements)
                       "\nCOMMIT;")]
    (run-sql batch-sql)))

(defn create-session! [id source]
  (transact-datoms! [[(str "session:" id) "source" source]
                     [(str "session:" id) "started_at" (/ (System/currentTimeMillis) 1000.0)]]))

(defn get-next-message-idx [session-id]
  (init-db!)
  (let [query (format "SELECT DISTINCT entity FROM datoms WHERE entity LIKE 'session:%s:message:%%';" session-id)
        rows (run-sql-json query)]
    (count rows)))

(defn add-messages! [session-id messages]
  (let [start-idx (get-next-message-idx session-id)]
    (transact-datoms!
     (mapcat (fn [idx msg]
               (let [entity (format "session:%s:message:%04d" session-id idx)
                     base [[entity "role" (:role msg)]
                           [entity "content" (:content msg)]
                           [entity "timestamp" (or (:timestamp msg) (/ (System/currentTimeMillis) 1000.0))]]
                     tool-calls (:tool_calls msg)]
                 (if tool-calls
                   (conj base [entity "tool_calls" (json/generate-string tool-calls)])
                   base)))
             (range start-idx (+ start-idx (count messages)))
             messages))))

(defn get-session-messages [session-id]
  (init-db!)
  (let [query (format "SELECT entity, attribute, value FROM datoms WHERE entity LIKE 'session:%s:message:%%' ORDER BY entity ASC;" session-id)
        rows (run-sql-json query)
        grouped (group-by :entity rows)]
    (->> (sort (keys grouped))
         (map (fn [entity]
                (let [attrs (into {} (map (fn [r] [(:attribute r) (:value r)]) (get grouped entity)))
                      base {:role (get attrs "role")
                            :content (get attrs "content")
                            :timestamp (Double/parseDouble (get attrs "timestamp"))}
                      tool-calls (get attrs "tool_calls")]
                  (if tool-calls
                    (assoc base :tool_calls (json/parse-string tool-calls true))
                    base))))
         vec)))

(defn save-system-state! [system]
  (let [cron-jobs (get system :cron-jobs {})
        skill-stats (get system :skill-stats {})]
    (transact-datoms! [["system:cron-jobs" "jobs" (json/generate-string (vals cron-jobs))]
                       ["system:skill-stats" "stats" (json/generate-string skill-stats)]])))

(defn update-skill-stats! [skill-name success?]
  (init-db!)
  (let [query "SELECT value FROM datoms WHERE entity = 'system:skill-stats' AND attribute = 'stats' ORDER BY tx DESC LIMIT 1;"
        rows (run-sql-json query)
        stats (if-let [v (:value (first rows))]
                (json/parse-string v true)
                {})
        current (get stats (keyword skill-name) {:hits 0 :successes 0})
        new-entry (-> current
                      (update :hits inc)
                      (update :successes (fn [s] (if success? (inc s) s))))
        new-stats (assoc stats (keyword skill-name) new-entry)]
    (transact-datoms! [["system:skill-stats" "stats" (json/generate-string new-stats)]])))

(defn save-checkpoint! [system]
  (let [id (get system :id "unknown")
        serializable (dissoc system :browser-process :registry)]
    (transact-datoms! [[(str "checkpoint:" id) "system-state" (json/generate-string serializable)]])))

(defn load-checkpoint [id]
  (init-db!)
  (let [query (format "SELECT value FROM datoms WHERE entity = 'checkpoint:%s' AND attribute = 'system-state' ORDER BY tx DESC LIMIT 1;" id)
        rows (run-sql-json query)]
    (if-let [v (:value (first rows))]
      (json/parse-string v true)
      nil)))
