(ns s18-voice-vision
  (:require [registry]
            [config]
            [store]
            [agent]
            [tools.multimedia]
            [clojure.string :as str]))

(defn handle-final-response [response]
  (if (str/starts-with? response "MEDIA:")
    (let [path (subs response 6)]
      (println (str "\n[SYSTEM] Media content detected: " path))
      (println (str "[VOICE] (Transcribed): " (slurp path))))
    (println (str "\nAssistant: " response))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s18: Voice & Vision (Babashka Port) ===")
    
    (let [session-id (str (java.util.UUID/randomUUID))
          agent-state (atom {:cached-prompt nil})
          budget (atom 90)]
      
      (println "Simulating Vision call...")
      (binding [registry/*session-id* session-id
                registry/*budget* budget
                registry/*config* runtime-config
                registry/*depth* 1]
        (let [res (agent/run-conversation session-id "Analyze this image: /path/to/cat.jpg and tell me what you see." agent-state)]
          (handle-final-response (:final-response res))))
      
      (println "\nSimulating TTS call...")
      (binding [registry/*session-id* session-id
                registry/*budget* budget
                registry/*config* runtime-config
                registry/*depth* 1]
        (let [res (agent/run-conversation session-id "Convert this to speech: 'Hello, how can I help you today?'" agent-state)]
          (handle-final-response (:final-response res))))
      
      (System/exit 0))))
