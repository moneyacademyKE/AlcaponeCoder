(ns s06-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [recovery]))

(deftest test-error-classification
  (testing "Rate limit"
    (let [c (recovery/classify-error 429 "Too Many Requests")]
      (is (= :rate-limit (:reason c)))
      (is (:retryable c))))
  
  (testing "Context overflow"
    (let [c (recovery/classify-error 400 "This model's maximum context length is...")]
      (is (= :context-overflow (:reason c)))
      (is (:should-compress c))))
  
  (testing "Auth failure"
    (let [c (recovery/classify-error 401 "Invalid API Key")]
      (is (= :auth (:reason c)))
      (is (not (:retryable c)))
      (is (:should-fallback c)))))

(deftest test-backoff
  (testing "Exponential backoff"
    (let [b1 (recovery/jittered-backoff 1)
          b2 (recovery/jittered-backoff 2)]
      (is (>= b1 2000))
      (is (>= b2 4000))
      (is (> b2 b1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
