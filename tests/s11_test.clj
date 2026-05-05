(ns s11-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [config]
            [clojure.java.io :as io]))

(deftest test-configuration
  (testing "Deep merge"
    (let [base {:a {:b 1 :c 2} :d 3}
          override {:a {:c 4} :e 5}
          merged (config/deep-merge base override)]
      (is (= 1 (get-in merged [:a :b])))
      (is (= 4 (get-in merged [:a :c])))
      (is (= 3 (:d merged)))
      (is (= 5 (:e merged)))))

  (testing "Env var expansion"
    (System/setProperty "TEST_VAR" "hello")
    (let [obj {:msg "Greeting: ${TEST_VAR}" :nested {:key "${TEST_VAR}"}}
          expanded (config/expand-env-vars obj)]
      (is (= "Greeting: hello" (:msg expanded)))
      (is (= "hello" (get-in expanded [:nested :key]))))
    (System/clearProperty "TEST_VAR"))

  (testing "Default config loading"
    (let [temp-home (io/file "/tmp/hermes-test")
          _ (.mkdirs temp-home)
          _ (when (.exists (io/file temp-home "config.yaml")) (.delete (io/file temp-home "config.yaml")))]
      (with-redefs [config/get-hermes-home (fn [] temp-home)]
        (let [c (config/load-config)]
          (is (= "anthropic/claude-sonnet-4" (:model c)))
          (is (= 90 (get-in c [:agent :max_turns]))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
