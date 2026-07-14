(ns store-test
  (:require [clojure.test :refer [deftest is testing]]
            [store]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(deftest test-sqlite-eav-store
  (let [temp-db "/tmp/hermes-test-state.db"]
    (io/delete-file temp-db true)
    (with-redefs [store/get-db-path (fn [] temp-db)]
      (testing "init-db! creates the datoms table"
        (store/init-db!)
        (is (.exists (io/file temp-db))))
      
      (testing "transact-datoms! inserts and run-sql-json queries datoms"
        (store/transact-datoms! [["session:123" "source" "harbor"]
                                 ["session:123" "started_at" "1620000000"]])
        (let [rows (store/run-sql-json "SELECT entity, attribute, value FROM datoms ORDER BY attribute ASC;")]
          (is (= 2 (count rows)))
          (is (= {:entity "session:123" :attribute "source" :value "harbor"} (first rows)))
          (is (= {:entity "session:123" :attribute "started_at" :value "1620000000"} (second rows)))))

      (testing "create-session! and add-messages! and get-session-messages work"
        (store/create-session! "123" "test-suite")
        (store/add-messages! "123" [{:role "user" :content "Hello world"}
                                    {:role "assistant" :content "Hi there" :tool_calls [{:id "1" :type "function"}]}])
        (let [msgs (store/get-session-messages "123")]
          (is (= 2 (count msgs)))
          (is (= "user" (:role (first msgs))))
          (is (= "Hello world" (:content (first msgs))))
          (is (= "assistant" (:role (second msgs))))
          (is (= "Hi there" (:content (second msgs))))
          (is (= [{:id "1" :type "function"}] (:tool_calls (second msgs))))))

      (testing "save-system-state! and update-skill-stats! work"
        (store/save-system-state! {:cron-jobs {"1" {:job-id "1" :prompt "test"}}
                                   :skill-stats {:my-skill {:hits 2 :successes 1}}})
        (store/update-skill-stats! "my-skill" true)
        (let [rows (store/run-sql-json "SELECT value FROM datoms WHERE entity = 'system:skill-stats' AND attribute = 'stats' ORDER BY tx DESC LIMIT 1;")
              parsed-stats (json/parse-string (:value (first rows)) true)]
          (is (= {:my-skill {:hits 3 :successes 2}} parsed-stats))))

      (testing "save-checkpoint! and load-checkpoint work"
        (store/save-checkpoint! {:id "123" :budget 95 :state {:turns 1}})
        (let [checkpoint (store/load-checkpoint "123")]
          (is (= 95 (:budget checkpoint)))
          (is (= {:turns 1} (:state checkpoint))))))))
