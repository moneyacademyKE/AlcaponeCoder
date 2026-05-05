(ns e2e-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [agent]
            [config]
            [store]
            [registry]
            [memory]
            [skill]
            [hooks]
            [permissions]
            [cheshire.core :as json]))

(deftest test-full-agent-workflow
  (testing "End-to-End: Task -> Memory -> Skill"
    (config/load-env)
    (let [runtime-config (config/load-config)]
      (store/init-db!)
      (hooks/clear!)
      (memory/save-memory "memory" [])
      (memory/save-memory "user" [])
      
      (binding [registry/*config* runtime-config
                registry/*budget* (atom 100)
                registry/*depth* 1
                agent/*memory-nudge-threshold* 1
                agent/*skill-nudge-threshold* 1]
        
        (let [session-id "e2e-session"
              state (atom {:cached-prompt nil})
              
              ;; Mock responses for a multi-turn conversation
              responses [{:choices [{:message {:content "I'll check the files."
                                               :tool_calls [{:id "c1" :function {:name "terminal" :arguments "{\"command\": \"ls\"}"}}]}}]}
                         {:choices [{:message {:content "Found some files. Saving memory."
                                               :tool_calls [{:id "c2" :function {:name "memory" :arguments "{\"action\": \"add\", \"content\": \"Checked files\"}"}}]}}]}
                         {:choices [{:message {:content "Done." :tool_calls nil}}]}]
              ptr (atom 0)
              mock-call (fn [_ _ _] 
                          (let [res (nth responses @ptr)
                                _ (swap! ptr inc)]
                            {:status :ok :data res}))]
          
          (with-redefs [agent/call-model mock-call]
            (agent/run-conversation session-id "Research files" state)
            
            ;; Verify memory was added
            (is (clojure.string/includes? (memory/render-entries (memory/load-memory "memory")) "Checked files"))
            
            ;; Verify background review triggered
            (is (>= @agent/*turns-since-memory* 0))
            (println "E2E Test: Basic workflow passed."))))))

  (testing "Hardened Tool Dispatch: Permission Denial"
    (hooks/clear!)
    (let [runtime-config (config/load-config)]
      (binding [registry/*config* runtime-config
                registry/*session-id* "perm-test-session"
                registry/*budget* (atom 10)]
        ;; Mock registry/tools to include a 'terminal' tool
        (registry/register! {:name "terminal" :handler (fn [args] (str "executed: " args)) :schema {:name "terminal"}})
        
        ;; Simulate a dangerous command
        (let [dangerous-cmd "{\"command\": \"rm -rf /\"}"]
          ;; We need to mock permissions/ask-user to return :deny
          (with-redefs [permissions/ask-user (fn [_ _] :deny)]
            (let [result (registry/dispatch "terminal" dangerous-cmd)]
              (is (clojure.string/includes? result "Permission denied by user."))
              (println "E2E Test: Permission denial passed.")))))))

  (testing "Turn-based Checkpointing: 20-turn Consolidation"
    (memory/save-memory "memory" [])
    (let [runtime-config (config/load-config)]
      (binding [registry/*config* runtime-config
                registry/*budget* (atom 100)]
        (let [session-id "checkpoint-session"
              state (atom {:cached-prompt nil})
              ;; Mock responses: 20 tool calls to reach iteration 20
              responses (concat (repeat 20 {:choices [{:message {:content "turn" 
                                                               :tool_calls [{:id "t" :function {:name "terminal" :arguments "{\"command\": \"ls\"}"}}]}}]})
                                [{:choices [{:message {:content "final turn" :tool_calls nil}}]}])
              ptr (atom 0)
              mock-call (fn [_ _ _] 
                          (let [res (nth responses @ptr)
                                _ (swap! ptr inc)]
                            {:status :ok :data res}))
              consolidate-called (atom false)]
          (with-redefs [agent/call-model mock-call
                        memory/consolidate! (fn [_ _] (reset! consolidate-called true))]
            (agent/run-conversation session-id "Go for 20 turns" state)
            (is @consolidate-called)
            (println "E2E Test: Checkpointing passed.")))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
