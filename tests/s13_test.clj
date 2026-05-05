(ns s13-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [adapters.base :as base]
            [gateway]
            [adapters.mock :as mock]))

(deftest test-adapter-utilities
  (testing "Deduplication"
    (let [dedup (base/create-deduplicator 2)]
      (is (not (base/duplicate? dedup "msg1")))
      (is (base/duplicate? dedup "msg1"))
      (is (not (base/duplicate? dedup "msg2")))
      (is (not (base/duplicate? dedup "msg3"))) ;; Evicts msg1
      (is (not (base/duplicate? dedup "msg1"))))) ;; msg1 is new again

  (testing "Text batching"
    (let [received (atom [])
          callback (fn [event] (swap! received conj (:text event)))]
      (base/enqueue-batch! "sess1" "Part 1" {} callback)
      (base/enqueue-batch! "sess1" " Part 2" {} callback)
      (Thread/sleep 1000)
      (is (= ["Part 1 Part 2"] @received)))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
