(ns s10-subagent-delegation
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [registry]
            [store]
            [agent]
            [delegation]
            [tools.terminal]))

;; ===========================================================================
;; Configuration
;; ===========================================================================

(def primary-config {:model (or (System/getenv "MODEL") "anthropic/claude-sonnet-4")
                     :base-url (or (System/getenv "OPENAI_BASE_URL") "https://openrouter.ai/api/v1")
                     :api-key (or (System/getenv "OPENAI_API_KEY") "")})

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (store/init-db!)
  (println "=== s10: Subagent Delegation (Babashka Port) ===")
  
  (let [session-id (str (java.util.UUID/randomUUID))
        agent-state (atom {:cached-prompt nil})
        budget (atom 90)]
    (store/create-session! session-id "cli")
    (println (str "Session ID: " session-id))
    
    (println "Type 'quit' to exit.\n")
    
    (loop []
      (print "You: ")
      (flush)
      (let [user-input (read-line)]
        (when-not (or (str/blank? user-input)
                      (contains? #{"quit" "exit"} (str/lower-case user-input)))
          (binding [registry/*session-id* session-id
                    registry/*budget* budget
                    registry/*config* primary-config
                    registry/*depth* 1]
            (let [result (agent/run-conversation session-id user-input agent-state)]
              (println (str "\nAssistant: " (:final-response result) "\n"))
              (recur))))))))
