(ns taste-test
  (:require [clojure.test :refer [deftest is testing]]
            [taste]
            [clojure.java.io :as io]))

(deftest test-profile-load-save
  (testing "Loading profile should return default if file doesn't exist"
    (let [temp-home (io/file "/tmp/hermes-taste-test-home")
          _ (System/setProperty "user.home" (.getAbsolutePath temp-home))
          ;; Delete existing taste file if any
          _ (io/delete-file (io/file temp-home ".hermes/taste.json") true)
          profile (taste/load-taste-profile)]
      (is (map? profile))
      (is (contains? profile :idioms))
      (is (contains? profile :anti-patterns))
      (is (= 1.0 (:success-score profile)))))

  (testing "Saving and loading profile should be consistent"
    (let [temp-home (io/file "/tmp/hermes-taste-test-home")
          _ (System/setProperty "user.home" (.getAbsolutePath temp-home))
          profile {:idioms ["Idiom 1"]
                   :anti-patterns ["Anti 1"]
                   :success-score 0.8}]
      (taste/save-taste-profile! profile)
      (is (= profile (taste/load-taste-profile))))))

(deftest test-reward-calculation
  (testing "evaluate-symbolic-reward"
    (is (= 1.0 (taste/evaluate-symbolic-reward {:exit 0 :out "All tests passed"})))
    (is (= 0.0 (taste/evaluate-symbolic-reward {:exit 1 :out "All tests passed"})))
    (is (= 0.0 (taste/evaluate-symbolic-reward {:exit 0 :out "FAIL: test-something"}))))

  (testing "calculate-reward weighted average"
    (let [system {}
          env {}
          cmd-out {:exit 0 :out "Passed"}]
      ;; We stub evaluate-neural-reward using with-redefs in implementation tests,
      ;; but we can verify calculate-reward directly if we redefine the neural evaluator.
      (with-redefs [taste/evaluate-neural-reward (fn [_ _ _] 0.5)]
        (is (= (+ (* 0.6 1.0) (* 0.4 0.5)) (taste/calculate-reward system env cmd-out "code")))))))

(deftest test-prompt-formatting
  (testing "format-taste-prompt formatting structure"
    (let [profile {:idioms ["Use Spec"] :anti-patterns ["Avoid atoms"] :success-score 1.0}
          prompt (taste/format-taste-prompt profile)]
      (is (clojure.string/includes? prompt "Use Spec"))
      (is (clojure.string/includes? prompt "Avoid atoms"))
      (is (clojure.string/includes? prompt "## Coding Taste & Idiomatic Constraints")))))
