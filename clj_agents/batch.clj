(ns batch
  (:require [agent]
            [trajectory]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn run-batch! [prompts output-path config]
  (println (str "Starting batch of " (count prompts) " prompts..."))
  (with-open [writer (io/writer output-path)]
    (doseq [[i prompt] (map-indexed vector prompts)]
      (println (str "  [" (inc i) "/" (count prompts) "] Processing: " (subs prompt 0 (min 50 (count prompt))) "..."))
      (let [agent-state (atom {:cached-prompt nil})
            session-id (str "batch-" i)
            result (try
                     (agent/run-conversation session-id prompt agent-state)
                     (catch Exception e {:error (ex-message e)}))]
        (if (:error result)
          (println "    FAILED:" (:error result))
          (let [messages (:messages result)
                traj (trajectory/convert-to-trajectory messages)
                stats (trajectory/extract-tool-stats messages)]
            (.write writer (json/generate-string {:prompt prompt
                                                  :trajectory traj
                                                  :tool_stats stats
                                                  :completed (some? (:final-response result))}))
            (.write writer "\n")
            (println "    OK"))))))
  (println (str "Batch complete. Trajectories saved to " output-path)))
