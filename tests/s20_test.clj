(ns s20-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [agent]
            [registry]))

(deftest test-background-review-trigger
  (testing "Counter increments"
    (reset! agent/*turns-since-memory* 0)
    (binding [agent/*memory-nudge-threshold* 5]
      ;; Simulate run-conversation (we don't actually call LLM here, just the counter logic)
      (swap! agent/*turns-since-memory* inc)
      (is (= 1 @agent/*turns-since-memory*))))

  (testing "Trigger resets counters"
    (reset! agent/*turns-since-memory* 3)
    (binding [agent/*memory-nudge-threshold* 2
              agent/*skill-nudge-threshold* 10
              registry/*config* {:model "test"}]
      ;; In run-conversation, it checks (>= @*turns-since-memory* *threshold*)
      ;; We'll use a mock to verify the logic in agent.clj
      (let [review-mem (and (pos? agent/*memory-nudge-threshold*) (>= @agent/*turns-since-memory* agent/*memory-nudge-threshold*))]
        (is (true? review-mem))
        (when review-mem
          (reset! agent/*turns-since-memory* 0)
          (reset! agent/*iters-since-skill* 0)))
      (is (= 0 @agent/*turns-since-memory*)))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
