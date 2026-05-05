(ns s19-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cli]
            [web]
            [config]
            [org.httpkit.client :as http]
            [cheshire.core :as json]))

(deftest test-cli-commands
  (testing "Command Registration"
    (let [called (atom false)]
      (cli/register-command! "test" "desc" (fn [_] (reset! called true)))
      (is (contains? @cli/commands "test")))))

(deftest test-web-api
  (testing "Web Server API"
    (let [stop-fn (web/start-server! 8081)]
      (try
        (let [resp @(http/get "http://localhost:8081/api/config")]
          (is (= 200 (:status resp)))
          (is (some? (json/parse-string (:body resp)))))
        (finally
          (stop-fn))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
