(ns s11-configuration-system
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [registry]
            [store]
            [agent]
            [delegation]
            [config]
            [tools.terminal]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s11: Configuration System (Babashka Port) ===")
    (println (str "Profile: " (.getAbsolutePath (config/get-hermes-home))))
    (println (str "Model: " (:model runtime-config)))
    
    (let [session-id (str (java.util.UUID/randomUUID))
          agent-state (atom {:cached-prompt nil})
          budget (atom (get-in runtime-config [:agent :max_turns] 90))]
      (store/create-session! session-id "cli")
      
      (println "Type 'quit' to exit.\n")
      
      (loop []
        (print "You: ")
        (flush)
        (let [user-input (read-line)]
          (when-not (or (str/blank? user-input)
                        (contains? #{"quit" "exit"} (str/lower-case user-input)))
            (binding [registry/*session-id* session-id
                      registry/*budget* budget
                      registry/*config* (assoc runtime-config :api-key (or (System/getProperty "OPENAI_API_KEY") 
                                                                          (System/getenv "OPENAI_API_KEY") 
                                                                          ""))
                      registry/*depth* 1]
              (let [result (agent/run-conversation session-id user-input agent-state)]
                (println (str "\nAssistant: " (:final-response result) "\n"))
                (recur)))))))))
