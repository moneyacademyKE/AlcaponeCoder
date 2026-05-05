(ns s02-tool-system
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [registry]
            ;; Import tools to trigger registration
            [tools.terminal]))

;; ===========================================================================
;; Configuration
;; ===========================================================================

(def base-url (or (System/getenv "OPENAI_BASE_URL") "https://openrouter.ai/api/v1"))
(def api-key (or (System/getenv "OPENAI_API_KEY") ""))
(def model (or (System/getenv "MODEL") "anthropic/claude-sonnet-4"))
(def max-iterations (Integer/parseInt (or (System/getenv "MAX_ITERATIONS") "30")))

(def system-prompt
  "You are a helpful assistant. You can use available tools to help the user.")

;; ===========================================================================
;; Core Loop (Updated to use registry)
;; ===========================================================================

(defn call-model [messages]
  (let [body (json/generate-string
              {:model model
               :messages (into [{:role "system" :content system-prompt}] messages)
               :tools (registry/get-definitions)})
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
        
        (let [msg-to-add (cond-> {:role "assistant"
                                  :content (or (:content assistant-msg) "")}
                           (:tool_calls assistant-msg)
                           (assoc :tool_calls (:tool_calls assistant-msg)))
              new-messages (conj messages msg-to-add)]
          
          (if-not (:tool_calls assistant-msg)
            {:final-response (:content assistant-msg) :messages new-messages}
            
            (let [tool-results (for [tc (:tool_calls assistant-msg)]
                                 (let [tool-name (get-in tc [:function :name])
                                       tool-args (get-in tc [:function :arguments])]
                                   (println (str "  [tool] " tool-name ": " tool-args))
                                   {:role "tool"
                                    :tool_call_id (:id tc)
                                    :content (registry/dispatch tool-name tool-args)}))]
              (recur (into new-messages tool-results) (inc iteration)))))))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (println "=== s02: Tool System (Babashka Port) ===")
  (println (str "Model: " model))
  (println (str "Base URL: " base-url))
  (println (str "Registered tools: " (str/join ", " (keys @registry/tools))))
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
