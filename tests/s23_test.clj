(ns s23-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [trajectory]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest test-trajectory-conversion
  (testing "OpenAI to ShareGPT conversion"
    (let [messages [{:role "system" :content "sys"}
                    {:role "user" :content "hi"}
                    {:role "assistant" :content "thought" :tool_calls [{:id "1" :function {:name "tool1" :arguments "{\"a\":1}"}}]}
                    {:role "tool" :tool_call_id "1" :content "res"}]
          traj (trajectory/convert-to-trajectory messages)]
      (is (= 4 (count traj)))
      (is (= "system" (:from (nth traj 0))))
      (is (= "human" (:from (nth traj 1))))
      (is (= "gpt" (:from (nth traj 2))))
      (is (str/includes? (:value (nth traj 2)) "<tool_call>"))
      (is (= "tool" (:from (nth traj 3))))
      (is (str/includes? (:value (nth traj 3)) "<tool_response>"))))

  (testing "Tool stats extraction"
    (let [messages [{:role "assistant" :tool_calls [{:id "1" :function {:name "ls" :arguments "{}"}}]}
                    {:role "tool" :tool_call_id "1" :content "file1"}]
          stats (trajectory/extract-tool-stats messages)]
      (is (= 1 (get-in stats ["ls" :count])))
      (is (= 1 (get-in stats ["ls" :success]))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
