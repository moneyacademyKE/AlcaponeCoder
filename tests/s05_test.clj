(ns s05-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [compression]
            [clojure.string :as str]))

(deftest test-compression-layers
  (testing "Layer 1: Pruning tool outputs"
    (let [messages [{:role "user" :content "hello"}
                    {:role "tool" :content "massive output 1"}
                    {:role "tool" :content "massive output 2"}
                    {:role "tool" :content "massive output 3"}
                    {:role "tool" :content "massive output 4"}]
          pruned (compression/prune-old-tool-results messages 2)]
      (is (= "[Old tool output cleared]" (:content (nth pruned 1))))
      (is (= "[Old tool output cleared]" (:content (nth pruned 2))))
      (is (= "massive output 3" (:content (nth pruned 3))))
      (is (= "massive output 4" (:content (nth pruned 4))))))

  (testing "Layer 2: Finding boundaries"
    (let [messages [{:role "user" :content "Task Goal"} ;; Head (0)
                    {:role "assistant" :content "Middle 1"} ;; Middle (1)
                    {:role "user" :content "Middle 2"} ;; Middle (2)
                    {:role "assistant" :content "Recent 1"} ;; Tail (3)
                    {:role "user" :content "Recent 2"}] ;; Tail (4)
          ;; Protect first 1, tail budget 2 tokens (Recent 2 only)
          [head-end tail-start] (compression/find-boundaries messages 1 2)]
      (is (= 1 head-end))
      (is (= 4 tail-start))))

  (testing "Layer 3: Full compression"
    (let [messages [{:role "user" :content "Goal: Fly to Mars"}
                    {:role "assistant" :content "How?"}
                    {:role "user" :content "Rocket"}
                    {:role "assistant" :content "Which one?"}
                    {:role "user" :content "Starship"}]
          mock-llm (fn [p] "SUMMARY_TEXT")
          ;; Protect 1, tail budget 2 (Starship only)
          compressed (compression/compress messages {:protect-first 1 
                                                     :tail-token-budget 2
                                                     :call-llm-fn mock-llm})]
      (is (= 3 (count compressed)))
      (is (= "Goal: Fly to Mars" (:content (first compressed))))
      (is (str/includes? (:content (second compressed)) "SUMMARY_TEXT"))
      (is (= "Starship" (:content (last compressed)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
