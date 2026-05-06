(ns test-system
  (:require [agent]
            [system]
            [llm]
            [clojure.string :as str]))

;; Mock LLM Call
(with-redefs [llm/call (fn [_system messages _prompt _model]
                        {:status :ok 
                         :data {:choices [{:message {:role "assistant" :content "1+1 is 2"}}]}})]
  (try
    (println "Testing Production-Grade System Map logic...")
    (let [system (system/create-system :session-id "test-session" 
                                      :config {:models {:primary "mock-model"}
                                               :agent {:max_turns 10}})]
      (println "System ID:" (:id system))
      (println "Running minimal logic check...")
      
      (let [res (agent/run-conversation system "What is 1+1?" (atom {}))]
        (println "Agent Response Received:" (:final-response res))
        (if (str/includes? (:final-response res) "2")
          (println "✅ System Logic Success")
          (println "❌ System Logic Failed")))
      
      (system/cleanup system)
      (println "Cleanup successful."))
    (catch Exception e
      (println "❌ System Test Error:" (ex-message e))
      (.printStackTrace e))))
