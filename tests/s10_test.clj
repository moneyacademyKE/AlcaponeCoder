(ns s10-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [agent]
            [registry]
            [delegation]
            [cheshire.core :as json]))

(deftest test-delegation
  (testing "Context isolation (subagent messages discarded)"
    (let [budget (atom 90)
          config {:model "mock" :base-url "mock" :api-key "mock"}
          agent-state (atom {:cached-prompt "Mock Prompt"})]
      (binding [registry/*budget* budget
                registry/*config* config
                registry/*depth* 1]
        ;; Mock call-model to return a direct response for subagent
        (with-redefs [agent/call-model (fn [_ _ _] {:status :ok :data {:choices [{:message {:content "Subagent Result"} :finish_reason "stop"}]}})]
          (let [res (delegation/delegate-task-tool {:goal "Test Goal"})]
            (is (= "Subagent Result" res))
            (is (= 89 @budget)))))))

  (testing "Depth limiting"
    (binding [registry/*depth* 2]
      (let [res (delegation/delegate-task-tool {:goal "Too Deep"})]
        (is (clojure.string/includes? res "Maximum delegation depth reached"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
