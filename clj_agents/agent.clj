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
            [llm]
            [store]
            [logger]
            [cron])
  (:import [java.util.concurrent Executors]))

(defn- spawn-background-review! [system messages review-memory review-skills]
  (println "\n[SYSTEM] Spawning background review thread...")
  (future
    (try
      (when review-memory
        (println "[BG-REVIEW] Consolidating memory...")
        (memory/consolidate! system messages (partial llm/call-auxiliary-llm system)))
      (when review-skills
        (println "[BG-REVIEW] Analyzing for new skills...")
        (reviewer/review-trajectory system messages))
      (println "[BG-REVIEW] Review completed.")
      (catch Exception e (println "BG Review failed:" (ex-message e))))))

(defn run-conversation [system user-message agent-state]
  (let [session-id (:id system)
        history (if session-id (store/get-session-messages session-id) [])
        initial-messages (conj history {:role "user" :content user-message})
        
        ;; Ensure counters are present — system/create-system always sets these,
        ;; but test systems may pass minimal maps, so we guard with fnil.
        now (System/currentTimeMillis)
        due-jobs (cron/get-due-jobs system now)
        system (assoc system :trace-id (str (java.util.UUID/randomUUID)))
        system (reduce (fn [sys job]
                         (println (str "[CRON] Firing job: " (:job-id job)))
                         ((cron/advance-job job now) sys))
                       system
                       due-jobs)

        system (-> system
                   (update-in [:state :turns-since-memory] (fnil identity 0))
                   (update-in [:state :iters-since-skill] (fnil identity 0))
                   (update-in [:state :plan] (fnil identity "No plan established yet.")))
        
        memory-threshold (get-in system [:config :agent :memory_nudge_threshold] 3)
        skill-threshold (get-in system [:config :agent :skill_nudge_threshold] 3)]
    
    (loop [messages initial-messages
           iteration 0
           retry-count 0
           continuation-count 0
           current-system system]
      (let [config (:config current-system)
            budget-val (get current-system :budget 100)]
        
        (if (or (>= iteration (get-in config [:agent :max_turns] 90)) (<= budget-val 0))
          {:final-response "(max iterations reached)" :messages messages :system current-system}
            (let [active-model-key (get current-system :active-model-key :primary)
                  plan (get-in current-system [:state :plan])
                  system-prompt (prompt/build-system-prompt current-system
                                                             {:soul (prompt/load-soul)
                                                              :plan plan
                                                              :memory (memory/format-for-system-prompt current-system "memory")
                                                              :user (memory/format-for-system-prompt current-system "user")
                                                              :skills (skill/get-skill-index-prompt)
                                                              :project-context (prompt/load-project-context ".")})
                
                ;; Decrement budget (always a value now)
                current-system (update current-system :budget dec)
                
                ;; Checkpointing (every 20 turns)
                _ (when (and (pos? iteration) (zero? (mod iteration 20)))
                    (logger/info current-system "checkpoint" {:turn iteration})
                    ;; Serialize system state to persistent store (Imperative Shell)
                    (store/save-system-state! current-system)
                    (memory/consolidate! current-system messages (partial llm/call-auxiliary-llm current-system)))

                ;; Proactive Compaction Check (15,000 + 10,000 char limit)
                char-count (compression/count-chars messages)
                threshold (get-in config [:compression :threshold_chars] 25000)
                messages (if (and (get-in config [:compression :enabled] true)
                                  (> char-count threshold))
                           (do (logger/info current-system "compaction_triggered" {:chars char-count})
                               (compression/compress messages 
                                 {:protect-first 1 
                                  :tail-char-budget 10000 
                                  :call-llm-fn (partial llm/call-auxiliary-llm current-system)}))
                           messages)

                response (llm/call current-system messages system-prompt active-model-key)]
            (cond
              (= :error (:status response))
              (let [classified (recovery/classify-error (:code response) (:message response))]
                (cond
                  (:should-compress classified)
                  (recur (compression/compress messages {:protect-first 1 :tail-char-budget 5000 :call-llm-fn (partial llm/call-auxiliary-llm current-system)})
                         iteration 0 0 current-system)
                  
                  (and (:should-fallback classified) (= active-model-key :primary))
                  (let [new-system (assoc current-system :active-model-key :fallback)]
                    (logger/warn current-system :auto_heal {:target :fallback :reason (:reason classified) :message (:message response)})
                    (recur messages iteration 0 0 new-system))

                  (and (:retryable classified) (< retry-count 10))
                  (if (and (= (:reason classified) :rate-limit) (>= retry-count 3) (= active-model-key :primary))
                    (let [new-system (assoc current-system :active-model-key :fallback)]
                      (logger/warn current-system :auto_heal {:target :fallback :reason "Excessive rate limits"})
                      (recur messages iteration 0 0 new-system))
                    (do (logger/warn current-system :api_retry {:attempt (inc retry-count) :reason (:reason classified)})
                        (Thread/sleep (long (recovery/jittered-backoff (inc retry-count))))
                        (recur messages iteration (inc retry-count) continuation-count current-system)))
                  
                  :else {:status :error :reason (:reason classified) :message (:message response) :system current-system}))

              (= "length" (get-in response [:data :choices 0 :finish_reason]))
              (recur (conj (conj messages (get-in response [:data :choices 0 :message])) 
                           {:role "user" :content recovery/continue-message}) 
                     iteration 0 (inc continuation-count) current-system)

              (>= retry-count 10)
              {:status :error :reason :max-retries :message (get-in response [:message]) :system current-system}

              :else
              (let [assistant-msg (get-in response [:data :choices 0 :message])
                    msg-to-add (cond-> {:role "assistant" :content (or (:content assistant-msg) "")}
                                 (:tool_calls assistant-msg) (assoc :tool_calls (:tool_calls assistant-msg)))
                    new-messages (conj messages msg-to-add)]
                (if-not (:tool_calls assistant-msg)
                  (let [new-system (-> current-system
                                       (update-in [:state :turns-since-memory] inc)
                                       (update-in [:state :iters-since-skill] inc))
                        mem-count (get-in new-system [:state :turns-since-memory])
                        skill-count (get-in new-system [:state :iters-since-skill])
                        review-mem (>= mem-count memory-threshold)
                        review-skill (>= skill-count skill-threshold)
                        
                        ;; Reset counters in the new system map
                        final-system (cond-> new-system
                                       review-mem (assoc-in [:state :turns-since-memory] 0)
                                       review-skill (assoc-in [:state :iters-since-skill] 0))]
                    
                    (when session-id
                      (let [delta (subvec new-messages (count history))]
                        (store/add-messages! session-id delta)))
                    
                    (when (or review-mem review-skill)
                      (spawn-background-review! final-system new-messages review-mem review-skill))
                    
                    {:final-response (:content assistant-msg) :messages new-messages :system final-system})

                  (let [tool-results (doall
                                      (pmap (fn [tc]
                                              (let [tool-name (get-in tc [:function :name])
                                                    tool-args (get-in tc [:function :arguments])]
                                                (logger/info current-system "tool_call_start" {:name tool-name :args tool-args})
                                                (hooks/emit! current-system :pre_tool_call {:name tool-name :args tool-args})
                                                (let [dispatch-res (registry/dispatch current-system tool-name tool-args)
                                                      result-str (if (map? dispatch-res) (:result dispatch-res) dispatch-res)
                                                      update-fn (if (map? dispatch-res) (:system-update dispatch-res) nil)]
                                                  (logger/info current-system "tool_call_end" {:name tool-name})
                                                  (hooks/emit! current-system :post_tool_call {:name tool-name :args tool-args :result result-str})
                                                  {:role "tool"
                                                   :tool_call_id (:id tc)
                                                   :content result-str
                                                   :system-update update-fn})))
                                            (:tool_calls assistant-msg)))
                        next-messages (into new-messages (map #(dissoc % :system-update) tool-results))
                        reduced-system (try
                                         (reduce (fn [sys tr]
                                                   (if-let [f (:system-update tr)]
                                                     (try
                                                       (f sys)
                                                       (catch Exception e
                                                         (throw (ex-info (str "Tool update failed: " (ex-message e))
                                                                         {:tool-result tr :original-error e}))))
                                                     sys))
                                                 current-system
                                                 tool-results)
                                         (catch Exception e
                                           (logger/error current-system "system_reduction_failed" {:error (ex-message e)})
                                           {:status :error :reason :system-corruption :message (ex-message e) :system current-system}))]
                    (if (= :error (:status reduced-system))
                      reduced-system
                      (recur next-messages (inc iteration) 0 0 reduced-system))))))))))))
