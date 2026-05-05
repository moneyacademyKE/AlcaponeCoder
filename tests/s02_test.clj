(ns s02-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [registry]
            [s02-tool-system :as agent]
            [cheshire.core :as json]))

(deftest test-tool-registry
  (testing "Tool self-registration"
    (is (contains? @registry/tools "terminal")))
  
  (testing "Get definitions returns valid schema"
    (let [defs (registry/get-definitions)]
      (is (seq defs))
      (is (= "terminal" (get-in (first defs) [:function :name])))))

  (testing "Dispatch routes to correct handler"
    (with-redefs [registry/tools (atom {"terminal" {:handler (fn [args] (let [a (json/parse-string args true)] (:command a)))}})]
      (let [result (registry/dispatch "terminal" (json/generate-string {:command "echo test"}))]
        (is (clojure.string/includes? result "test"))))))

(deftest test-agent-loop-with-registry
  (testing "Loop uses registry for tools"
    (let [responses [{:choices [{:message {:content nil
                                          :tool_calls [{:id "call_1"
                                                        :function {:name "terminal"
                                                                   :arguments (json/generate-string {:command "echo agent"})}}]}}]}
                     {:choices [{:message {:content "Done" :tool_calls nil}}]}]
          ptr (atom 0)
          mock-call (fn [_] (let [res (nth responses @ptr)] (swap! ptr inc) res))]
      (with-redefs [agent/call-model mock-call]
        (let [result (agent/run-conversation "Run echo")]
          (is (= "Done" (:final-response result)))
          (is (= 4 (count (:messages result)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
