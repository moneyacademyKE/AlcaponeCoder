(ns s23-trajectory-rl
  (:require [batch]
            [config]
            [store]
            [registry]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s23: Trajectory & RL Training (Babashka Port) ===")
    
    (let [prompts ["Write a hello world in python"
                   "List files in /tmp"]
          output-file "trajectories.jsonl"]
      
      (binding [registry/*config* runtime-config
                registry/*budget* (atom 100)
                registry/*depth* 1]
        
        (batch/run-batch! prompts output-file runtime-config))
      
      (println "\nInspecting first trajectory:")
      (with-open [r (io/reader output-file)]
        (let [first-line (.readLine r)
              data (json/parse-string first-line true)]
          (println "Prompt:" (:prompt data))
          (println "Turns:" (count (:trajectory data)))
          (println "Tools used:" (:tool_stats data))))
      
      (io/delete-file output-file)
      (System/exit 0))))
