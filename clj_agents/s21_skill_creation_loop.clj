(ns s21-skill-creation-loop
  (:require [agent]
            [config]
            [store]
            [registry]
            [skill]
            [memory]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s21: Skill Creation Loop (Babashka Port) ===")
    
    (let [session-id "s21-demo"
          agent-state (atom {:cached-prompt nil})]
      
      (binding [registry/*config* runtime-config
                registry/*budget* (atom 100)
                registry/*depth* 1
                agent/*memory-nudge-threshold* 0
                agent/*skill-nudge-threshold* 5] ;; Trigger after 5 tool calls
        
        (println "\n--- Simulating a complex task with tool calls ---")
        ;; We'll use a prompt that likely triggers multiple terminal/read_file calls
        (agent/run-conversation session-id "Research and document how to set up a Clojure project with Babashka and test it." agent-state)
        
        ;; If the agent made >= 5 tool calls, background review triggers
        (println "\nWaiting for background skill review thread...")
        (Thread/sleep 2000))
      
      (System/exit 0))))
