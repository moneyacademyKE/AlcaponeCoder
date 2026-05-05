(ns store
  (:require [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def db-path "state.db")
(def sqlite-exe "/usr/bin/sqlite3")

(defn query [sql]
  (let [{:keys [out err exit]} (shell {:out :string :err :string :in sql :continue true} sqlite-exe "-json" db-path)]
    (if (zero? exit)
      (if (str/blank? out) [] (json/parse-string out true))
      (throw (Exception. (str "SQLite error: " err " SQL: " sql))))))

(defn execute! [sql]
  (let [{:keys [err exit]} (shell {:err :string :in sql :continue true} sqlite-exe db-path)]
    (when-not (zero? exit)
      (throw (Exception. (str "SQLite error: " err " SQL: " sql))))))

(defn init-db! []
  (execute! "PRAGMA journal_mode=WAL;")
  (execute! "CREATE TABLE IF NOT EXISTS sessions (
               id TEXT PRIMARY KEY,
               source TEXT NOT NULL,
               started_at REAL NOT NULL
             );")
  (execute! "CREATE TABLE IF NOT EXISTS messages (
               id INTEGER PRIMARY KEY AUTOINCREMENT,
               session_id TEXT NOT NULL REFERENCES sessions(id),
               role TEXT NOT NULL,
               content TEXT,
               tool_calls TEXT,
               tool_call_id TEXT,
               timestamp REAL NOT NULL
             );"))

(defn create-session! [id source]
  (execute! (format "INSERT INTO sessions (id, source, started_at) VALUES ('%s', '%s', %f);"
                    id source (float (/ (System/currentTimeMillis) 1000.0)))))

(defn add-messages! [session-id messages]
  (doseq [msg messages]
    (let [content (str/replace (or (:content msg) "") "'" "''")
          tool-calls (if (:tool_calls msg) (str/replace (json/generate-string (:tool_calls msg)) "'" "''") "NULL")
          tool-call-id (or (:tool_call_id msg) "NULL")]
      (execute! (format "INSERT INTO messages (session_id, role, content, tool_calls, tool_call_id, timestamp)
                         VALUES ('%s', '%s', '%s', %s, %s, %f);"
                        session-id
                        (:role msg)
                        content
                        (if (= tool-calls "NULL") "NULL" (str "'" tool-calls "'"))
                        (if (= tool-call-id "NULL") "NULL" (str "'" tool-call-id "'"))
                        (float (/ (System/currentTimeMillis) 1000.0)))))))

(defn get-session-messages [session-id]
  (let [rows (query (format "SELECT role, content, tool_calls, tool_call_id FROM messages WHERE session_id = '%s' ORDER BY id" session-id))]
    (vec (map (fn [r]
                (cond-> {:role (:role r) :content (:content r)}
                  (:tool_calls r) (assoc :tool_calls (json/parse-string (:tool_calls r) true))
                  (:tool_call_id r) (assoc :tool_call_id (:tool_call_id r))))
              rows))))
