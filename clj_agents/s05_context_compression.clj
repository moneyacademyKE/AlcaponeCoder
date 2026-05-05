(ns s05-context-compression
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [registry]
            [store]
            [prompt]
            [compression]
            [tools.terminal]))

;; ===========================================================================
;; Configuration
;; ===========================================================================

(def base-url (or (System/getenv "OPENAI_BASE_URL") "https://openrouter.ai/api/v1"))
(def api-key (or (System/getenv "OPENAI_API_KEY") ""))
(def model (or (System/getenv "MODEL") "anthropic/claude-sonnet-4"))
(def aux-model (or (System/getenv "AUX_MODEL") "google/gemini-flash-1.5"))
(def max-iterations (Integer/parseInt (or (System/getenv "MAX_ITERATIONS") "30")))
(def token-threshold 10000) ;; Lowered for demonstration

;; ===========================================================================
;; Core Loop (Updated with Context Compression)
;; ===========================================================================

(defn call-model [messages system-prompt target-model]
  (let [body (json/generate-string
              {:model target-model
               :messages (into [{:role "system" :content system-prompt}] messages)
               :tools (when (= target-model model) (registry/get-definitions))})
        response (http/post (str base-url "/chat/completions")
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body body})]
    (json/parse-string (:body response) true)))

(defn call-auxiliary-llm [prompt-text]
  (let [res (call-model [{:role "user" :content prompt-text}] 
                        "You are a helpful assistant summarizing conversation history." 
                        aux-model)]
    (get-in res [:choices 0 :message :content])))

(defn run-conversation [session-id user-message agent-state]
  (let [history (store/get-session-messages session-id)
        initial-messages (conj history {:role "user" :content user-message})]
    
    (loop [messages initial-messages
           iteration 0]
      ;; Check for compression
      (let [current-tokens (compression/estimate-tokens messages)
            effective-messages (if (> current-tokens token-threshold)
                                 (do
                                   (println (str "  [system] Context threshold reached (" current-tokens " tokens). Compressing..."))
                                   (let [new-msgs (compression/compress 
                                                   messages 
                                                   {:protect-first 1
                                                    :tail-token-budget 2000
                                                    :call-llm-fn call-auxiliary-llm})]
                                     ;; When compressed, we should technically store this as a new session segment
                                     ;; but for simplicity in this chapter we just continue with new-msgs.
                                     new-msgs))
                                 messages)
            system-prompt (or (:cached-prompt @agent-state)
                             (let [p (prompt/build-system-prompt
                                      {:soul (prompt/load-soul)
                                       :project-context (prompt/load-project-context ".")})]
                               (swap! agent-state assoc :cached-prompt p)
                               p))]
        
        (if (>= iteration max-iterations)
          {:final-response "(max iterations reached)" :messages effective-messages}
          (let [response (call-model effective-messages system-prompt model)
                choice (first (:choices response))
                assistant-msg (:message choice)]
            
            (let [msg-to-add (cond-> {:role "assistant"
                                      :content (or (:content assistant-msg) "")}
                               (:tool_calls assistant-msg)
                               (assoc :tool_calls (:tool_calls assistant-msg)))
                  new-messages (conj effective-messages msg-to-add)]
              
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
                  (recur (into new-messages tool-results) (inc iteration)))))))))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (store/init-db!)
  (println "=== s05: Context Compression (Babashka Port) ===")
  
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
