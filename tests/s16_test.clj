(ns s16-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [mcp]
            [registry]
            [clojure.string :as str]))

(deftest test-mcp-integration
  (testing "MCP Tool Registration"
    (let [mock-server (mcp/connect-stdio "test" "bb" ["/Users/moe/.gemini/antigravity/brain/a2949aac-66a6-4c0a-a1d6-7f91b70b8db0/scratch/mock_mcp_server.clj"] {})]
      (mcp/register-mcp-tools! mock-server)
      (is (contains? @registry/tools "mcp_test_echo"))
      
      (testing "MCP Tool Invocation"
        (let [res (registry/dispatch "mcp_test_echo" "{\"text\": \"hello\"}")]
          (is (str/includes? res "MOCK ECHO: hello")))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
