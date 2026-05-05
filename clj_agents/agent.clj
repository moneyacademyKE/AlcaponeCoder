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
            [hooks]
            [reviewer]
            [llm])
  (:import [java.util.concurrent Executors]))

(def ^:dynamic *turns-since-memory* (atom 0))
(def ^:dynamic *iters-since-skill* (atom 0))
(def ^:dynamic *memory-nudge-threshold* 3)
(def ^:dynamic *skill-nudge-threshold* 3)

(defn- spawn-background-review! [messages review-memory review-skills]
  (println "\n[SYSTEM] Spawning background review thread...")
  (future
    (try
      (binding [*memory-nudge-threshold* 0
                *skill-nudge-threshold* 0]
        (when review-memory
          (println "[BG-REVIEW] Consolidating memory...")
          (memory/consolidate! messages llm/call-auxiliary-llm))
        (when review-skills
          (println "[BG-REVIEW] Analyzing for new skills...")
          (reviewer/review-trajectory messages))
        (println "[BG-REVIEW] Review completed."))
      (catch Exception e (println "BG Review failed:" (ex-message e))))))

(defn run-conversation [session-id user-message agent-state]
  (swap! *turns-since-memory* inc)
  (let [history (if session-id (store/get-session-messages session-id) [])
        initial-messages (conj history {:role "user" :content user-message})]
    (loop [messages initial-messages
           iteration 0
           retry-count 0
           continuation-count 0
           current-config registry/*config*]
      (if (or (>= iteration (get-in current-config [:agent :max_turns] 90)) (<= @registry/*budget* 0))
        {:final-response "(max iterations reached)" :messages messages}
        (let [system-prompt (prompt/build-system-prompt
                              {:soul (prompt/load-soul)
                               :memory (memory/format-for-system-prompt "memory")
                               :user (memory/format-for-system-prompt "user")
                               :skills (skill/get-skill-index-prompt)
                               :project-context (prompt/load-project-context ".")})
              _ (swap! registry/*budget* dec)
              
              ;; Checkpointing (every 20 turns)
              _ (when (and (pos? iteration) (zero? (mod iteration 20)))
                  (println "\n[SYSTEM] Reached 20-turn checkpoint. Consolidating memory...")
                  (memory/consolidate! messages llm/call-auxiliary-llm))

              ;; Proactive Compaction Check
              token-estimate (compression/estimate-tokens messages)
              threshold (get-in current-config [:compression :threshold_tokens] 25000)
              messages (if (and (get-in current-config [:compression :enabled] true)
                                (> token-estimate threshold))
                         (do (println (str "\n[SYSTEM] Proactive compaction triggered (" (int token-estimate) " tokens)..."))
                             (compression/compress messages 
                               {:protect-first 1 
                                :tail-token-budget 10000 
                                :call-llm-fn (fn [p] 
                                               (let [compaction-cfg (assoc current-config :model (or (:fallback-model current-config) "inclusionai/ling-2.6-1t:free"))
                                                     res (llm/call-model [{:role "user" :content p}] "You are a precise summarizer." compaction-cfg)]
                                                 (if (= :ok (:status res))
                                                   (get-in res [:data :choices 0 :message :content])
                                                   "Failed to summarize history.")))}))
                         messages)

              response (llm/call-model messages system-prompt current-config)]
          (cond
            (= :error (:status response))
            (let [classified (recovery/classify-error (:code response) (:message response))]
              (cond
                (:should-compress classified)
                (recur (compression/compress messages {:protect-first 1 :tail-token-budget 5000 :call-llm-fn llm/call-auxiliary-llm}) 
                       iteration 0 0 current-config)
                
                (and (:should-fallback classified) (:fallback-model current-config))
                (let [new-config (assoc current-config :model (:fallback-model current-config))]
                  (println (str "\n[AUTO-HEAL] Switching to fallback model: " (:model new-config)))
                  (recur messages iteration 0 0 new-config))

                (and (:retryable classified) (< retry-count 10))
                (do (println (str "\n[RETRY] API error (" (:reason classified) "), retrying... (attempt " (inc retry-count) ")"))
                    (Thread/sleep (long (recovery/jittered-backoff (inc retry-count))))
                    (recur messages iteration (inc retry-count) continuation-count current-config))
                
                :else (throw (Exception. (str "Terminal API error: " (:message response))))))

            (= "length" (get-in response [:data :choices 0 :finish_reason]))
            (recur (conj (conj messages (get-in response [:data :choices 0 :message])) 
                         {:role "user" :content recovery/continue-message}) 
                   iteration 0 (inc continuation-count) current-config)

            (>= retry-count 10)
            (throw (Exception. (str "Max retries reached: " (:message response))))

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
                  (recur (into new-messages tool-results) (inc iteration) 0 0 current-config))))))))))
