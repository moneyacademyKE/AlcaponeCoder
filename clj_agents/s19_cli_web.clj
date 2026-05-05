(ns s19-cli-web
  (:require [cli]
            [web]
            [config]
            [store]
            [agent]
            [cron]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (cron/load-jobs!)
    (println "=== s19: CLI & Web Interface (Babashka Port) ===")
    
    ;; Start Web API in background
    (web/start-server! 8080)
    
    (println "Interactive CLI started. Type /help for commands.")
    (loop []
      (print "You: ")
      (flush)
      (let [input (read-line)]
        (when input
          (if (clojure.string/starts-with? input "/")
            (cli/dispatch-command input)
            (do
              (cli/show-status "Thinking...")
              (let [session-id "cli-session"
                    agent-state (atom {:cached-prompt nil})]
                (agent/run-conversation session-id input agent-state))))
          (recur))))))
