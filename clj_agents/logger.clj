(ns logger
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; Log to ~/.hermes/hermes.log so it is stable regardless of CWD (critical in container benchmarks)
(defn- get-log-dir []
  (let [dir (io/file (System/getProperty "user.home") ".hermes")]
    (.mkdirs dir)
    dir))

(defn- log-file []
  (io/file (get-log-dir) "hermes.log"))

(defn- agent-log-file []
  ;; Also write to /logs/agent/ if available (Harbor benchmark log mount)
  (let [f (io/file "/logs/agent/hermes_bb.txt")]
    (when (.exists (.getParentFile f)) f)))

(defn- mask-secrets [data]
  (let [secret-patterns #{"api_key" "token" "password" "secret" "auth" "credential"}]
    (cond
      (map? data) (into {} (for [[k v] data] 
                             [k (if (some #(str/includes? (str/lower-case (name k)) %) secret-patterns)
                                  "REDACTED" 
                                  (mask-secrets v))]))
      (vector? data) (mapv mask-secrets data)
      :else data)))

(defn- log! [level system event-type data]
  (let [clean-data (mask-secrets data)
        entry {:timestamp (str (java.time.Instant/now))
               :level level
               :session_id (:id system)
               :trace_id (:trace-id system)
               :event_type event-type
               :data clean-data}
        json-line (json/generate-string entry)
        human-prefix (str "[" (.toUpperCase (name level)) "] " 
                          (when-let [tid (:trace-id system)] (str "(" tid ") "))
                          event-type " - ")
        human-line (str human-prefix (json/generate-string clean-data) "\n")]
    ;; Structured JSON log — stable path
    (spit (log-file) (str json-line "\n") :append true)
    ;; Harbor benchmark agent log (if mount exists)
    (when-let [af (agent-log-file)]
      (spit af human-line :append true))
    ;; Terminal output
    (println (str human-prefix (json/generate-string clean-data)))))

(defn info [system event-type data] (log! :info system event-type data))
(defn warn [system event-type data] (log! :warn system event-type data))
(defn error [system event-type data] (log! :error system event-type data))
(defn debug [system event-type data] (log! :debug system event-type data))
