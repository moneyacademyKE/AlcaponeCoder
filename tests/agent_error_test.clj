(ns agent-error-test
  (:require [clojure.test :refer [deftest is testing]]
            [agent]
            [llm]))

(deftest test-agent-error-handling
  (testing "Agent loop should return error map instead of throwing on API failure"
    (let [system {:config {:agent {:max_turns 10}}
                  :budget 100
                  :state {:turns-since-memory 0}}]
      (with-redefs [llm/call (fn [_ _ _ _] {:status :error :code 401 :message "Unauthorized"})]
        (let [result (agent/run-conversation system "Hi" (atom {}))]
          (is (= :error (:status result)))
          (is (= "Unauthorized" (:message result)))
          (is (= :auth (:reason result))))))))
