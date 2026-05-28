(ns agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [agent]
            [llm]
            [backend]))

(deftest test-agent-state-transitions
  (testing "Agent loop should update turns-since-memory correctly"
    (let [system {:config {:agent {:memory_nudge_threshold 10}}
                  :budget 100
                  :state {:turns-since-memory 0}}]
      (with-redefs [llm/call (fn [_ _ _ _] {:status :success :data {:choices [{:message {:role "assistant" :content "Hello"}}]}})]
        (let [result (agent/run-conversation system "Hi" (atom {}))
              new-system (:system result)]
          (is (= 1 (get-in new-system [:state :turns-since-memory])))
          (is (= 1 (get-in new-system [:state :iters-since-skill])))))))

  (testing "Agent loop should reset counters after threshold reached"
    (let [system {:config {:agent {:memory_nudge_threshold 1}}
                  :budget 100
                  :state {:turns-since-memory 0}}]
      (with-redefs [llm/call (fn [_ _ _ _] {:status :success :data {:choices [{:message {:role "assistant" :content "Hello"}}]}})
                    agent/spawn-background-review! (fn [& _] nil)]
        (let [result (agent/run-conversation system "Hi" (atom {}))
              new-system (:system result)]
          ;; It should have reached 1, then reset to 0
          (is (= 0 (get-in new-system [:state :turns-since-memory]))))))))

(deftest test-heredoc-execution
  (testing "Command wrapping should correctly handle heredocs and comments without syntax errors"
    (let [env (backend/create-local-env)
          _ (backend/run-bash env "export TEST_VAR=my-value")
          res (backend/run-bash env "cat <<'EOS'\nhello world\nEOS")
          res2 (backend/run-bash env "echo $TEST_VAR")]
      (is (= 0 (:exit res)))
      (is (= "hello world\n" (:out res)) "Output should only contain the heredoc output, not wrapper code")
      (is (= "my-value\n" (:out res2)) "Environment variables must persist across command executions"))))

