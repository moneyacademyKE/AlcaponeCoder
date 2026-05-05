(ns s20-background-review
  (:require [agent]
            [config]
            [store]
            [registry]
            [memory]
            [skill]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s20: Background Review (Babashka Port) ===")
    
    (let [session-id "s20-demo"
          agent-state (atom {:cached-prompt nil})]
      
      (binding [registry/*config* runtime-config
                registry/*budget* (atom 100)
                registry/*depth* 1
                agent/*memory-nudge-threshold* 2 ;; Trigger after 2 turns
                agent/*skill-nudge-threshold* 10]
        
        (println "\n--- Turn 1 ---")
        (agent/run-conversation session-id "Hello! I like coffee." agent-state)
        
        (println "\n--- Turn 2 ---")
        (agent/run-conversation session-id "What is my favorite drink?" agent-state)
        ;; After this turn, background review should trigger
        
        (println "\nWaiting for background review thread...")
        (Thread/sleep 2000))
      
      (System/exit 0))))
