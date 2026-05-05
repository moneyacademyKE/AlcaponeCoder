(ns s01-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [s01-agent-loop :as agent]
            [cheshire.core :as json]))

;; Mock the API call to avoid network dependency and API keys
(defn mock-call-model [responses]
  (let [ptr (atom 0)]
    (fn [messages]
      (let [res (nth responses @ptr)]
        (swap! ptr inc)
        res))))

(deftest test-agent-loop-completion
  (testing "Loop terminates when no tool calls are present"
    (with-redefs [agent/call-model (mock-call-model [{:choices [{:message {:content "Hello world" :tool_calls nil}}]}])]
      (let [result (agent/run-conversation "Hi")]
        (is (= "Hello world" (:final-response result)))
        (is (= 2 (count (:messages result)))))))

  (testing "Loop continues and executes tools"
    (let [responses [{:choices [{:message {:content nil
                                          :tool_calls [{:id "call_1"
                                                        :function {:name "terminal"
                                                                   :arguments (json/generate-string {:command "echo hello"})}}]}}]}
                     {:choices [{:message {:content "Done" :tool_calls nil}}]}]
          mock-tool (fn [name args] "hello")]
      (with-redefs [agent/call-model (mock-call-model responses)
                    agent/run-tool mock-tool]
        (let [result (agent/run-conversation "Run echo")]
          (is (= "Done" (:final-response result)))
          ;; user + assistant (tool call) + tool result + assistant (final) = 4 messages
          (is (= 4 (count (:messages result))))
          (is (= "tool" (:role (nth (:messages result) 2)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
