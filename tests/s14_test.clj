(ns s14-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [backend]
            [clojure.string :as str]))

(deftest test-terminal-backend
  (testing "State persistence (cd)"
    (let [env (backend/create-local-env)]
      (try
        ;; 1. Initial dir
        (let [res1 (backend/run-bash env "pwd")]
          (is (= 0 (:exit res1)))
          (let [initial-dir (str/trim (:out res1))]
            ;; 2. cd /tmp
            (let [res2 (backend/run-bash env "cd /tmp")]
              (is (= 0 (:exit res2)))
              ;; 3. check pwd
              (let [res3 (backend/run-bash env "pwd")]
                (is (= "/private/tmp" (str/trim (:out res3)))))))) ;; /tmp is usually /private/tmp on mac
        (finally
          (backend/cleanup env)))))

  (testing "Environment variable persistence"
    (let [env (backend/create-local-env)]
      (try
        (backend/run-bash env "export TEST_VAL=123")
        (let [res (backend/run-bash env "echo $TEST_VAL")]
          (is (= "123" (str/trim (:out res)))))
        (finally
          (backend/cleanup env))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
