(ns s26-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [eval]))

(deftest test-constraints
  (testing "Valid skill"
    (let [res (eval/validate-constraints "# Title\nbody")]
      (is (true? (:valid? res)))))

  (testing "Invalid skill - empty"
    (let [res (eval/validate-constraints "")]
      (is (false? (:valid? res)))
      (is (some #(= "Empty skill text" %) (:errors res)))))

  (testing "Invalid skill - structure"
    (let [res (eval/validate-constraints "Title\nbody")]
      (is (false? (:valid? res)))
      (is (some #(= "Invalid skill structure" %) (:errors res))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
