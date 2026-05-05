(ns permissions
  (:require [clojure.string :as str]))

(def dangerous-patterns
  [[#"(?i)\brm\s+-[^\s]*r" "recursive delete"]
   [#"(?i)\bmkfs\b" "format filesystem"]
   [#"(?i)\bDROP\s+(TABLE|DATABASE)\b" "SQL DROP"]
   [#"(?i)\bDELETE\s+FROM\b(?!.*\bWHERE\b)" "SQL DELETE without WHERE"]
   [#"(?i)\b(curl|wget)\b.*\|\s*(ba)?sh\b" "pipe remote content to shell"]
   [#"(?i)\bgit\s+reset\s+--hard\b" "git reset --hard"]])

(defonce session-approvals (atom {})) ;; {session-id #{description}}
(defonce permanent-approvals (atom #{})) ;; Load from config in real system

(defn detect-danger [command]
  (let [cmd-lower (str/lower-case command)]
    (some (fn [[pattern desc]]
            (when (re-find pattern cmd-lower)
              desc))
          dangerous-patterns)))

(defn ask-user [command description]
  (println "\n========================================")
  (println "⚠️  DANGEROUS COMMAND DETECTED")
  (println (str "Description: " description))
  (println (str "Command:     " command))
  (println "----------------------------------------")
  (println "Options: [o]nce / [s]ession / [a]lways / [d]eny")
  (print "Choice: ")
  (flush)
  (let [choice (str/lower-case (or (read-line) "d"))]
    (case choice
      "o" :once
      "s" :session
      "a" :always
      :deny)))

(defn check-permission [session-id command]
  (if-let [desc (detect-danger command)]
    (cond
      (contains? (get @session-approvals session-id) desc) true
      (contains? @permanent-approvals desc) true
      (= "true" (System/getenv "HEADLESS")) (do (println "[SYSTEM] Headless mode: Auto-approving dangerous command.") true)
      :else (let [choice (ask-user command desc)]
              (case choice
                :once true
                :session (do (swap! session-approvals update session-id (fnil conj #{}) desc) true)
                :always (do (swap! permanent-approvals conj desc) true)
                false)))
    true))
