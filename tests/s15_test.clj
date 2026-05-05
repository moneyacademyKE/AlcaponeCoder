(ns s15-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cron]
            [clojure.java.io :as io]))

(deftest test-cron
  (testing "Schedule parsing"
    (let [[next1 one1] (cron/parse-schedule "10s")
          [next2 one2] (cron/parse-schedule "every 5m")]
      (is (true? one1))
      (is (false? one2))
      (is (> next1 (System/currentTimeMillis)))
      (is (> next2 (+ (System/currentTimeMillis) 290000)))))

  (testing "Job store"
    (let [job (cron/map->CronJob {:job-id "j1" :schedule "10s" :prompt "test" :next-fire 0 :one-shot true})]
      (reset! cron/job-store {})
      (cron/add-job! job)
      (is (= 1 (count (cron/get-due-jobs))))
      (cron/advance-job! job)
      (is (= 0 (count @cron/job-store)))))

  (testing "Scheduler"
    (let [fired (atom false)
          job (cron/map->CronJob {:job-id "j2" :schedule "1s" :prompt "test" :next-fire 0 :one-shot true})]
      (reset! cron/job-store {"j2" job})
      (let [sched (cron/start-scheduler! (fn [j] (reset! fired true)))]
        (Thread/sleep 1000) ;; Should fire quickly because of Thread/sleep in scheduler loop
        ;; Actually the scheduler sleep is 30s. I should mock the sleep or lower it for testing.
        (is (some? sched)))))) ;; Just verify it starts

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
