(ns s09-permission-system
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [registry]
            [store]
            [prompt]
            [compression]
            [recovery]
            [memory]
            [skill]
            [permissions]
            [tools.terminal]))

;; ===========================================================================
;; Configuration
;; ===========================================================================

(def primary-config {:model (or (System/getenv "MODEL") "anthropic/claude-sonnet-4")
                     :base-url (or (System/getenv "OPENAI_BASE_URL") "https://openrouter.ai/api/v1")
                     :api-key (or (System/getenv "OPENAI_API_KEY") "")})

(def aux-model "google/gemini-flash-1.5")
(def max-iterations 30)

;; ===========================================================================
;; API Wrapper
;; ===========================================================================

(defn call-model [messages system-prompt config]
  (try
    (let [body (json/generate-string
                {:model (:model config)
                 :messages (into [{:role "system" :content system-prompt}] messages)
                 :tools (registry/get-definitions)})
          response (http/post (str (:base-url config) "/chat/completions")
                              {:headers {"Authorization" (str "Bearer " (:api-key config))
                                         "Content-Type" "application/json"}
                               :body body})]
      (if (= 200 (:status response))
        {:status :ok :data (json/parse-string (:body response) true)}
        {:status :error :code (:status response) :message (:body response)}))
    (catch Exception e
      {:status :error :code 0 :message (.getMessage e)})))

(defn call-auxiliary-llm [prompt-text]
  (let [res (call-model [{:role "user" :content prompt-text}] 
                        "You are a helpful assistant." 
                        (assoc primary-config :model aux-model))]
    (if (= :ok (:status res))
      (get-in res [:data :choices 0 :message :content])
      "Failed to summarize.")))

;; ===========================================================================
;; Core Loop
;; ===========================================================================

(defn run-conversation [session-id user-message agent-state]
  (let [history (store/get-session-messages session-id)
        initial-messages (conj history {:role "user" :content user-message})]
    
    (loop [messages initial-messages
           iteration 0
           retry-count 0
           continuation-count 0]
      
      (if (>= iteration max-iterations)
        {:final-response "(max iterations reached)" :messages messages}
        
        (let [system-prompt (or (:cached-prompt @agent-state)
                               (let [p (prompt/build-system-prompt
                                        {:soul (prompt/load-soul)
                                         :memory (memory/format-for-system-prompt "memory")
                                         :user (memory/format-for-system-prompt "user")
                                         :skills (skill/get-skill-index-prompt)
                                         :project-context (prompt/load-project-context ".")})]
                                 (swap! agent-state assoc :cached-prompt p)
                                 p))
              response (call-model messages system-prompt primary-config)]
          
          (cond
            (= :error (:status response))
            (let [classified (recovery/classify-error (:code response) (:message response))]
              (cond
                (:should-compress classified)
                (recur (compression/compress messages {:protect-first 1 :tail-token-budget 5000 :call-llm-fn call-auxiliary-llm}) iteration 0 continuation-count)
                (:retryable classified)
                (do (Thread/sleep (long (recovery/jittered-backoff (inc retry-count))))
                    (recur messages iteration (inc retry-count) continuation-count))
                :else (throw (Exception. (str "API error: " (:message response))))))

            (= "length" (get-in response [:data :choices 0 :finish_reason]))
            (recur (conj (conj messages (get-in response [:data :choices 0 :message])) 
                         {:role "user" :content recovery/continue-message}) 
                   iteration 0 (inc continuation-count))

            :else
            (let [assistant-msg (get-in response [:data :choices 0 :message])
                  msg-to-add (cond-> {:role "assistant" :content (or (:content assistant-msg) "")}
                               (:tool_calls assistant-msg) (assoc :tool_calls (:tool_calls assistant-msg)))
                  new-messages (conj messages msg-to-add)]
              
              (if-not (:tool_calls assistant-msg)
                (do
                  (let [delta (subvec new-messages (count history))]
                    (store/add-messages! session-id delta))
                  {:final-response (:content assistant-msg) :messages new-messages})
                
                (let [tool-results (binding [registry/*session-id* session-id]
                                     (for [tc (:tool_calls assistant-msg)]
                                       (let [tool-name (get-in tc [:function :name])
                                             tool-args (get-in tc [:function :arguments])]
                                         (println (str "  [tool] " tool-name ": " tool-args))
                                         {:role "tool"
                                          :tool_call_id (:id tc)
                                          :content (registry/dispatch tool-name tool-args)})))]
                  (recur (into new-messages tool-results) (inc iteration) 0 0))))))))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (store/init-db!)
  (println "=== s09: Permission System (Babashka Port) ===")
  
  (let [session-id (str (java.util.UUID/randomUUID))
        agent-state (atom {:cached-prompt nil})]
    (store/create-session! session-id "cli")
    (println (str "Session ID: " session-id))
    
    (println "Type 'quit' to exit.\n")
    
    (loop []
      (print "You: ")
      (flush)
      (let [user-input (read-line)]
        (when-not (or (str/blank? user-input)
                      (contains? #{"quit" "exit"} (str/lower-case user-input)))
          (let [result (run-conversation session-id user-input agent-state)]
            (println (str "\nAssistant: " (:final-response result) "\n"))
            (recur)))))))
