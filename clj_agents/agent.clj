(ns agent
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
            [hooks])
  (:import [java.util.concurrent Executors]))

(def ^:dynamic *turns-since-memory* (atom 0))
(def ^:dynamic *iters-since-skill* (atom 0))
(def ^:dynamic *memory-nudge-threshold* 3) ;; Low for demo
(def ^:dynamic *skill-nudge-threshold* 3)

(defn- spawn-background-review! [messages review-memory review-skills]
  (let [prompt (cond (and review-memory review-skills) "Review for memory and skills."
                     review-memory "Review this conversation for memories to save."
                     :else "Review this conversation for new skills.")]
    (println "\n[SYSTEM] Spawning background review thread...")
    (future
      (try
        (binding [*memory-nudge-threshold* 0 ;; Cascade protection
                  *skill-nudge-threshold* 0]
          (let [agent-state (atom {:cached-prompt nil})]
            (println (str "[BG-REVIEW] Starting: " prompt))
            ;; In a real system, we'd run a separate agent instance
            ;; Here we just simulate it to avoid recursive API calls in tests/demo
            (println "[BG-REVIEW] Review completed.")))
        (catch Exception e (println "BG Review failed:" (.getMessage e)))))))

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
                        registry/*config*)]
    (if (= :ok (:status res))
      (get-in res [:data :choices 0 :message :content])
      "Failed to summarize.")))

(defn run-conversation [session-id user-message agent-state]
  (swap! *turns-since-memory* inc)
  (let [history (if session-id (store/get-session-messages session-id) [])
        initial-messages (conj history {:role "user" :content user-message})]
    
    (loop [messages initial-messages
           iteration 0
           retry-count 0
           continuation-count 0]
      
      (if (or (>= iteration 30) (<= @registry/*budget* 0))
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
              _ (swap! registry/*budget* dec)
              response (call-model messages system-prompt registry/*config*)]
          
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
                  (when session-id
                    (let [delta (subvec new-messages (count history))]
                      (store/add-messages! session-id delta)))
                  (let [final-resp {:final-response (:content assistant-msg) :messages new-messages}]
                    ;; Check trigger
                    (let [review-mem (and (pos? *memory-nudge-threshold*) (>= @*turns-since-memory* *memory-nudge-threshold*))
                          review-skill (and (pos? *skill-nudge-threshold*) (>= @*iters-since-skill* *skill-nudge-threshold*))]
                      (when (or review-mem review-skill)
                        (reset! *turns-since-memory* 0)
                        (reset! *iters-since-skill* 0)
                        (spawn-background-review! new-messages review-mem review-skill)))
                    final-resp))
                
                (let [tool-results (for [tc (:tool_calls assistant-msg)]
                                     (let [tool-name (get-in tc [:function :name])
                                           tool-args (get-in tc [:function :arguments])]
                                        (println (str "  " (str/join (repeat registry/*depth* "  ")) "[tool] " tool-name ": " tool-args))
                                        (swap! *iters-since-skill* inc)
                                        (hooks/emit! :pre_tool_call {:name tool-name :args tool-args})
                                        (let [result (registry/dispatch tool-name tool-args)]
                                          (hooks/emit! :post_tool_call {:name tool-name :args tool-args :result result})
                                          {:role "tool"
                                           :tool_call_id (:id tc)
                                           :content result})))]
                  (recur (into new-messages tool-results) (inc iteration) 0 0))))))))))
