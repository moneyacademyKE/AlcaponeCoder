(ns agent-error-test
  (:require [clojure.test :refer [deftest is testing]]
            [agent]
            [llm]
            [babashka.http-client :as http]))

(deftest test-agent-error-handling
  (testing "Agent loop should return error map instead of throwing on API failure"
    (let [system {:config {:agent {:max_turns 10}}
                  :budget 100
                  :state {:turns-since-memory 0}}]
      (with-redefs [llm/call (fn [_ _ _ _] {:status :error :code 401 :message "Unauthorized"})]
        (let [result (agent/run-conversation system "Hi" (atom {}))]
          (is (= :error (:status result)))
          (is (= "Unauthorized" (:message result)))
          (is (= :auth (:reason result))))))))

(deftest test-200-ok-error-parsing
  (testing "llm/call-model should parse HTTP 200 responses containing errors or empty choices as errors"
    (let [system {:config {:base-url "https://api.example.com" :api-key "test-key"}}]
      ;; Case 1: 200 OK with error field in JSON body
      (with-redefs [http/post (fn [_ _] {:status 200 :body "{\"error\":{\"message\":\"Rate limit reached\",\"code\":429}}"})]
        (let [res (llm/call-model system [] "Prompt" "model-id")]
          (is (= :error (:status res)))
          (is (= 429 (:code res)))
          (is (clojure.string/includes? (:message res) "Rate limit reached"))))
      
      ;; Case 2: 200 OK with empty choices in JSON body
      (with-redefs [http/post (fn [_ _] {:status 200 :body "{\"choices\":[]}"})]
        (let [res (llm/call-model system [] "Prompt" "model-id")]
          (is (= :error (:status res)))
          (is (= 500 (:code res)))
          (is (clojure.string/includes? (:message res) "No choices returned")))))))

(deftest test-fast-fallback
  (testing "Agent should switch active-model-key to :fallback after 1 agent-level rate limit failure"
    (let [system {:config {:agent {:max_turns 2 :retry_limit 2}
                           :models {:primary "primary-model"
                                    :fallback "fallback-model"}}
                  :budget 10
                  :active-model-key :primary
                  :state {:turns-since-memory 0}}]
      ;; We redefine llm/call to fail on primary model and succeed on fallback model
      (with-redefs [llm/call (fn [_ _ _ model-key]
                               (if (= model-key :primary)
                                 {:status :error :code 429 :message "Rate limited"}
                                 {:status :ok :data {:choices [{:message {:content "Fallback success"}}]}}))]
        (let [result (agent/run-conversation system "Hi" (atom {}))]
          (is (= "Fallback success" (:final-response result)))
          (is (= :fallback (:active-model-key (:system result)))))))))

(deftest test-llm-call-retry-limit
  (testing "llm/call should retry up to 3 times for :primary and up to config limit for other keys"
    (let [system {:config {:agent {:retry_limit 5}
                           :base-url "https://api.example.com" :api-key "test-key"
                           :models {:primary "primary-model" :fallback "fallback-model"}}}
          calls (atom 0)]
      (with-redefs [llm/call-model (fn [_ _ _ _] 
                                     (swap! calls inc) 
                                     {:status :error :code 429 :message "Rate limit"})
                    llm/sleep (fn [_] nil)]
        ;; Primary key
        (reset! calls 0)
        (let [res (llm/call system [] "Prompt" :primary)]
          (is (= :error (:status res)))
          (is (= 3 @calls) "Should only retry 3 times for primary model"))
        
        ;; Fallback key
        (reset! calls 0)
        (let [res (llm/call system [] "Prompt" :fallback)]
          (is (= :error (:status res)))
          (is (= 5 @calls) "Should retry up to config limit (5) for fallback model"))))))


