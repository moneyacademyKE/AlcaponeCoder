(ns s09-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [permissions]
            [registry]
            [tools.terminal]
            [cheshire.core :as json]))

(deftest test-permission-system
  (testing "Dangerous command detection"
    (is (= "recursive delete" (permissions/detect-danger "rm -rf /tmp/test")))
    (is (= "SQL DROP" (permissions/detect-danger "DROP TABLE users")))
    (is (nil? (permissions/detect-danger "ls -la"))))

  (testing "Permission check logic (mocking ask-user)"
    (let [session-id "test-sess"]
      ;; Reset approvals
      (reset! permissions/session-approvals {})
      
      (with-redefs [permissions/ask-user (fn [_ _] :once)]
        (is (permissions/check-permission session-id "rm -rf /tmp/once")))
      
      (with-redefs [permissions/ask-user (fn [_ _] :deny)]
        (is (not (permissions/check-permission session-id "rm -rf /tmp/deny"))))
      
      (with-redefs [permissions/ask-user (fn [_ _] :session)]
        (is (permissions/check-permission session-id "rm -rf /tmp/sess1"))
        ;; Second time should be auto-approved
        (is (permissions/check-permission session-id "rm -rf /tmp/sess2")))))

  (testing "Terminal tool integration"
    (let [session-id "test-sess-tool"]
      (reset! permissions/session-approvals {})
      (binding [registry/*session-id* session-id]
        (with-redefs [permissions/ask-user (fn [_ _] :deny)]
          (let [res (tools.terminal/handler (json/generate-string {:command "rm -rf /tmp/tool-deny"}))]
            (is (clojure.string/includes? res "Permission denied"))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
