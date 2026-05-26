(ns spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [system]
            [clojure.spec.alpha :as s]))

(deftest test-system-spec-validation
  (testing "Valid system map should pass validation"
    (let [valid-sys {:id "session-123"
                     :config {:agent {:max_turns 90}}
                     :budget 90
                     :depth 0
                     :env {}
                     :registry {}
                     :hooks {}
                     :approvals {}
                     :cron-jobs {}
                     :skill-stats {}
                     :state {:turns-since-memory 0
                             :iters-since-skill 0
                             :plan "My plan"}
                     :browser-process (atom nil)}]
      ;; Should return the system map itself if valid
      (is (= valid-sys (system/validate-system valid-sys)))))

  (testing "Invalid system map should throw exception with details"
    (let [invalid-sys-budget {:id "session-123"
                              :config {}
                              :budget "ninety" ;; Invalid: budget must be a number
                              :depth 0
                              :env {}
                              :registry {}
                              :hooks {}
                              :approvals {}
                              :cron-jobs {}
                              :skill-stats {}
                              :state {:turns-since-memory 0
                                      :iters-since-skill 0
                                      :plan "My plan"}
                              :browser-process (atom nil)}
          invalid-sys-registry {:id "session-123"
                                :config {}
                                :budget 90
                                :depth 0
                                :env {}
                                :registry (atom {}) ;; Invalid: registry must be a map, not atom
                                :hooks {}
                                :approvals {}
                                :cron-jobs {}
                                :skill-stats {}
                                :state {:turns-since-memory 0
                                        :iters-since-skill 0
                                        :plan "My plan"}
                                :browser-process (atom nil)}]
      (is (thrown-with-msg? Exception #"System validation failed"
            (system/validate-system invalid-sys-budget)))
      (is (thrown-with-msg? Exception #"System validation failed"
            (system/validate-system invalid-sys-registry))))))
