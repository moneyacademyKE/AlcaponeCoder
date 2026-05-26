#!/usr/bin/env bb

(ns pilot
  (:require [clojure.string :as str]
            [config]
            [store]
            [agent]
            [system]
            [logger])
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.reader LineReaderBuilder Completer Candidate UserInterruptException EndOfFileException]))

(def pilot-commands ["/help" "/budget" "/plan" "/history" "/reset" "/exit" "/quit"])

(def pilot-completer
  (reify Completer
    (complete [this reader line candidates]
      (let [word (.word line)]
        (doseq [cmd pilot-commands]
          (when (or (empty? word) (.startsWith cmd word))
            (.add candidates (Candidate. cmd))))))))

(defn print-welcome []
  (println "\u001B[1;36m┌────────────────────────────────────────────────────────┐\u001B[0m")
  (println "\u001B[1;36m│          HERMES AGENT — PILOT CONTROL PANEL            │\u001B[0m")
  (println "\u001B[1;36m│      (Babashka v1.12+ • Rich Hickey Certified)         │\u001B[0m")
  (println "\u001B[1;36m└────────────────────────────────────────────────────────┘\u001B[0m")
  (println "\u001B[90mPress [Tab] to see slash commands. Ctrl+D or /exit to quit.\u001B[0m\n"))

(defn handle-command [cmd sys]
  (case cmd
    "/help"
    (do (println "\n\u001B[1;33mAvailable Commands:\u001B[0m")
        (println "  /help     - Show this help message")
        (println "  /budget   - Print the remaining turn budget")
        (println "  /plan     - Print the agent's current plan")
        (println "  /history  - Display the conversation message history")
        (println "  /reset    - Reset session state and message history")
        (println "  /exit     - Close pilot session")
        (println)
        sys)
    
    "/budget"
    (do (println (str "\n\u001B[1;32mCurrent Budget:\u001B[0m " (:budget sys) " turns left.\n"))
        sys)

    "/plan"
    (do (println (str "\n\u001B[1;32mCurrent Plan:\u001B[0m\n" (get-in sys [:state :plan]) "\n"))
        sys)

    "/history"
    (do (println "\n\u001B[1;32mMessage History:\u001B[0m")
        (let [msgs (store/get-session-messages (:id sys))]
          (if (empty? msgs)
            (println "  (No messages in history yet)")
            (doseq [m msgs]
              (println (str "  [" (.toUpperCase (:role m)) "]: " (:content m))))))
        (println)
        sys)

    "/reset"
    (let [new-sys (system/create-system :config (:config sys))]
      (println "\n\u001B[1;31mSession state reset successfully.\u001B[0m\n")
      new-sys)

    (do (println (str "\n\u001B[1;31mUnknown command: " cmd "\u001B[0m\n"))
        sys)))

(defn start-pilot-loop [cfg]
  (let [terminal (-> (TerminalBuilder/builder)
                     (.build))
        reader   (-> (LineReaderBuilder/builder)
                     (.terminal terminal)
                     (.completer pilot-completer)
                     (.build))]
    (print-welcome)
    (store/init-db!)
    (try
      (loop [sys (system/create-system :config cfg)]
        (let [prompt-str (str "\u001B[1;35mhermes(" (:budget sys) ")> \u001B[0m")
              line (try
                     (.readLine reader prompt-str)
                     (catch UserInterruptException e nil)
                     (catch EndOfFileException e nil))]
          (cond
            (or (nil? line) (contains? #{"/exit" "/quit"} (str/trim line)))
            (println "\nGoodbye!")

            (str/starts-with? (str/trim line) "/")
            (recur (handle-command (str/trim line) sys))

            (empty? (str/trim line))
            (recur sys)

            :else
            (do
              (println "\n\u001B[1;33m[AGENT START] Executing instruction...\u001B[0m")
              (let [result (agent/run-conversation sys (str/trim line) (:state sys))]
                (if (= :error (:status result))
                  (do
                    (println (str "\n\u001B[1;31m[AGENT ERROR] " (:reason result) ": " (:message result) "\u001B[0m\n"))
                    (recur sys))
                  (do
                    (println (str "\n\u001B[1;32m[AGENT RESPONSE]:\u001B[0m\n" (:final-response result) "\n"))
                    (recur (:system result)))))))))
      (catch Exception e
        (println (str "\nFatal Error: " (ex-message e)))
        (.printStackTrace e))
      (finally
        (.close terminal)))))

(defn -main []
  (config/load-env)
  (let [cfg (config/load-config)]
    (start-pilot-loop cfg)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
