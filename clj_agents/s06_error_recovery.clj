(ns s06-error-recovery
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [registry]
            [store]
            [prompt]
            [compression]
            [recovery]
            [tools.terminal]))

;; ===========================================================================
;; Configuration
;; ===========================================================================

(def primary-config {:model (or (System/getenv "MODEL") "anthropic/claude-sonnet-4")
                     :base-url (or (System/getenv "OPENAI_BASE_URL") "https://openrouter.ai/api/v1")
                     :api-key (or (System/getenv "OPENAI_API_KEY") "")})

(def fallback-config {:model "google/gemini-flash-1.5"
                      :base-url "https://openrouter.ai/api/v1"
                      :api-key (or (System/getenv "OPENAI_API_KEY") "")})

(def aux-model "google/gemini-flash-1.5")
(def max-iterations 30)

;; ===========================================================================
;; API Wrapper with Recovery
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
           continuation-count 0
           current-config primary-config]
      
      (if (>= iteration max-iterations)
        {:final-response "(max iterations reached)" :messages messages}
        
        (let [system-prompt (or (:cached-prompt @agent-state)
                               (let [p (prompt/build-system-prompt
                                        {:soul (prompt/load-soul)
                                         :project-context (prompt/load-project-context ".")})]
                                 (swap! agent-state assoc :cached-prompt p)
                                 p))
              response (call-model messages system-prompt current-config)]
          
          (cond
            ;; Case 1: API Error
            (= :error (:status response))
            (let [classified (recovery/classify-error (:code response) (:message response))]
              (println (str "  [system] API Error: " (:reason classified) " (" (:code response) ")"))
              (cond
                (:should-compress classified)
                (let [new-msgs (compression/compress messages {:protect-first 1 
                                                                :tail-token-budget 5000 
                                                                :call-llm-fn call-auxiliary-llm})]
                  (recur new-msgs iteration 0 continuation-count current-config))
                
                (and (:should-fallback classified) (not= current-config fallback-config))
                (do
                  (println "  [system] Falling back to secondary model...")
                  (recur messages iteration 0 continuation-count fallback-config))
                
                (and (:retryable classified) (< retry-count 3))
                (let [wait (recovery/jittered-backoff (inc retry-count))]
                  (println (str "  [system] Retrying in " (int wait) "ms..."))
                  (Thread/sleep (long wait))
                  (recur messages iteration (inc retry-count) continuation-count current-config))
                
                :else
                (throw (Exception. (str "Unrecoverable API error: " (:message response))))))

            ;; Case 2: Truncated Output
            (= "length" (get-in response [:data :choices 0 :finish_reason]))
            (if (< continuation-count 3)
              (do
                (println "  [system] Output truncated. Continuing...")
                (let [partial-msg (get-in response [:data :choices 0 :message])
                      new-msgs (conj messages (assoc partial-msg :role "assistant"))
                      continue-msgs (conj new-msgs {:role "user" :content recovery/continue-message})]
                  (recur continue-msgs iteration 0 (inc continuation-count) current-config)))
              (throw (Exception. "Max continuations reached.")))

            ;; Case 3: Success
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
                
                (let [tool-results (for [tc (:tool_calls assistant-msg)]
                                     (let [tool-name (get-in tc [:function :name])
                                           tool-args (get-in tc [:function :arguments])]
                                       (println (str "  [tool] " tool-name ": " tool-args))
                                       {:role "tool"
                                        :tool_call_id (:id tc)
                                        :content (registry/dispatch tool-name tool-args)}))]
                  (recur (into new-messages tool-results) (inc iteration) 0 0 current-config))))))))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (store/init-db!)
  (println "=== s06: Error Recovery (Babashka Port) ===")
  
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
