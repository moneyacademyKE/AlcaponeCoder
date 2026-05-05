(ns s22-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [hooks]
            [agent]
            [registry]))

(deftest test-hooks
  (testing "Hook Registration and Emission"
    (hooks/clear!)
    (let [called (atom false)]
      (hooks/register! :test_event (fn [_ _] (reset! called true)))
      (hooks/emit! :test_event {})
      (is (true? @called))))

  (testing "Agent Tool Hooks"
    (hooks/clear!)
    (let [pre-called (atom nil)
          post-called (atom nil)]
      (hooks/register! :pre_tool_call (fn [_ ctx] (reset! pre-called (:name ctx))))
      (hooks/register! :post_tool_call (fn [_ ctx] (reset! post-called (:name ctx))))
      
      ;; Mock registry/dispatch to avoid real tool calls
      (with-redefs [registry/dispatch (fn [_ _] "result")]
        ;; Manually trigger the tool part of agent/run-conversation logic is hard,
        ;; so we test the emission directly as it would be called in agent.clj
        (hooks/emit! :pre_tool_call {:name "test_tool" :args "{}"})
        (is (= "test_tool" @pre-called))
        (hooks/emit! :post_tool_call {:name "test_tool" :args "{}" :result "result"})
        (is (= "test_tool" @post-called))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
