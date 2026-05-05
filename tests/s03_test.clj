(ns s03-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [store]
            [s03-session-store :as agent]
            [cheshire.core :as json]
            [babashka.process :refer [shell]]))

(deftest test-persistence
  (shell "/bin/rm" "-f" "test_state.db")
  (with-redefs [store/db-path "test_state.db"]
    (store/init-db!)
    
    (testing "Create and retrieve session"
      (let [sid "test-session"]
        (store/create-session! sid "test")
        (store/add-messages! sid [{:role "user" :content "hello"}
                                  {:role "assistant" :content "hi"}])
        (let [history (store/get-session-messages sid)]
          (is (= 2 (count history)))
          (is (= "user" (:role (first history))))
          (is (= "hi" (:content (second history)))))))
    
    (testing "Agent loop uses history"
      (let [sid "test-session-2"
            responses [{:choices [{:message {:content "I remember you said hello" :tool_calls nil}}]}]
            ptr (atom 0)
            mock-call (fn [msgs] 
                        (is (> (count msgs) 1)) ;; Should have history
                        (let [res (nth responses @ptr)] (swap! ptr inc) res))]
        (store/create-session! sid "test")
        (store/add-messages! sid [{:role "user" :content "my name is Moe"}])
        
        (with-redefs [agent/call-model mock-call]
          (let [result (agent/run-conversation sid "who am I?")]
            (is (= "I remember you said hello" (:final-response result)))
            ;; history(1) + user(1) + assistant(1) = 3
            (is (= 3 (count (:messages result))))
            
            ;; Verify database has all 3 messages now
            (is (= 3 (count (store/get-session-messages sid))))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
