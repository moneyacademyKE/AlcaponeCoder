(ns s27-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [optimizer]
            [eval]))

(deftest test-optimizer
  (testing "Optimization Loop"
    ;; Mock evaluation to always show improvement
    (with-redefs [eval/llm-judge (fn [c _ _ _] 
                                   (let [score (if (clojure.string/includes? c "feedback") 1.0 0.5)]
                                     (eval/->FitnessScore score 0.8 0.9 "add more feedback")))]
      (let [result (optimizer/optimize "start" nil 2)]
        (is (pos? (:improvement result)))
        (is (clojure.string/includes? (:evolved-text result) "feedback"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
