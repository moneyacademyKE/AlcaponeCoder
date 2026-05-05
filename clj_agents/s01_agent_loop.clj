(ns s01-agent-loop
  (:require [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ===========================================================================
;; Configuration
;; ===========================================================================

(def base-url (or (System/getenv "OPENAI_BASE_URL") "https://openrouter.ai/api/v1"))
(def api-key (or (System/getenv "OPENAI_API_KEY") ""))
(def model (or (System/getenv "MODEL") "anthropic/claude-sonnet-4"))
(def max-iterations (Integer/parseInt (or (System/getenv "MAX_ITERATIONS") "30")))

(def system-prompt
  "You are a helpful assistant. You can run shell commands via the terminal tool.")

(def tools
  [{:type "function"
    :function {:name "terminal"
               :description "Run a shell command and return its output."
               :parameters {:type "object"
                            :properties {:command {:type "string"
                                                   :description "The shell command to execute"}}
                            :required ["command"]}}}])

;; ===========================================================================
;; Tool Execution
;; ===========================================================================

(def blocked-commands ["rm -rf /" "mkfs" "dd if=" "shutdown" "reboot"])

(defn run-tool [name arguments]
  (let [args (json/parse-string arguments true)]
    (case name
      "terminal"
      (let [command (:command args)]
        (if (some #(str/includes? command %) blocked-commands)
          (json/generate-string {:error (str "Blocked: " command)})
          (try
            (let [{:keys [out err]} (shell {:out :string :err :string :continue true} command)]
              (let [output (str out err)]
                (if (empty? output)
                  "(no output)"
                  (subs output 0 (min (count output) 10000)))))
            (catch Exception e
              (str "(error: " (.getMessage e) ")")))))
      (json/generate-string {:error (str "Unknown tool: " name)}))))

;; ===========================================================================
;; Core Conversation Loop
;; ===========================================================================

(defn call-model [messages]
  (let [body (json/generate-string
              {:model model
               :messages (into [{:role "system" :content system-prompt}] messages)
               :tools tools})
        response (http/post (str base-url "/chat/completions")
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body body})]
    (json/parse-string (:body response) true)))

(defn run-conversation [user-message]
  (loop [messages [{:role "user" :content user-message}]
         iteration 0]
    (if (>= iteration max-iterations)
      {:final-response "(max iterations reached)" :messages messages}
      (let [response (call-model messages)
            choice (first (:choices response))
            assistant-msg (:message choice)]
        
        ;; Prepare the assistant message for our history
        (let [msg-to-add (cond-> {:role "assistant"
                                  :content (or (:content assistant-msg) "")}
                           (:tool_calls assistant-msg)
                           (assoc :tool_calls (:tool_calls assistant-msg)))
              new-messages (conj messages msg-to-add)]
          
          (if-not (:tool_calls assistant-msg)
            {:final-response (:content assistant-msg) :messages new-messages}
            
            ;; Execute tools and recur
            (let [tool-results (for [tc (:tool_calls assistant-msg)]
                                 (let [tool-name (get-in tc [:function :name])
                                       tool-args (get-in tc [:function :arguments])]
                                   (println (str "  [tool] " tool-name ": " tool-args))
                                   {:role "tool"
                                    :tool_call_id (:id tc)
                                    :content (run-tool tool-name tool-args)}))]
              (recur (into new-messages tool-results) (inc iteration)))))))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (println "=== s01: Minimal Agent Loop (Babashka Port) ===")
  (println (str "Model: " model))
  (println (str "Base URL: " base-url))
  (println "Type 'quit' to exit.\n")
  
  (loop []
    (print "You: ")
    (flush)
    (let [user-input (read-line)]
      (when-not (or (str/blank? user-input)
                    (contains? #{"quit" "exit"} (str/lower-case user-input)))
        (let [result (run-conversation user-input)]
          (println (str "\nAssistant: " (:final-response result) "\n"))
          (recur))))))
