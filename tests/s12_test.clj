(ns s12-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [gateway]
            [adapters.mock :as mock]))

(deftest test-gateway
  (testing "Session key generation"
    (let [src-dm (gateway/map->SessionSource {:platform "tg" :chat-id "123" :chat-type "dm" :user-id "123"})
          src-group (gateway/map->SessionSource {:platform "wecom" :chat-id "grp1" :chat-type "group" :user-id "user1"})]
      (is (= "agent:main:tg:dm:123" (gateway/build-session-key src-dm)))
      (is (= "agent:main:wecom:group:grp1:user1" (gateway/build-session-key src-group)))))

  (testing "Adapter routing (mock)"
    (let [runner (gateway/create-gateway-runner {})
          adapter (mock/create-mock-adapter "test")
          received (promise)]
      (with-redefs [gateway/handle-message (fn [_ event] (deliver received event) "Reply")]
        (gateway/register-adapter! runner "test" adapter)
        (mock/push-message! adapter {:text "Test Msg" :chat-id "c1" :chat-type "dm" :user-id "u1"})
        (let [event (deref received 2000 nil)]
          (is (not (nil? event)))
          (is (= "Test Msg" (:text event)))
          (is (= "test" (get-in event [:source :platform]))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
