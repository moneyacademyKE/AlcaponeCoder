(ns skill-extraction-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [reviewer]
            [agent]
            [skill]
            [llm]
            [cheshire.core :as json]))

(deftest test-skill-extraction
  (testing "Reviewer: Pattern Discovery"
    (let [trajectory [{:role "user" :content "How do I check open ports?"}
                      {:role "assistant" :content "I will use netstat." :tool_calls [{:id "t1" :function {:name "terminal" :arguments "{\"command\": \"netstat -tuln\"}"}}]}
                      {:role "tool" :content "Active Internet connections..."}
                      {:role "assistant" :content "The open ports are listed above."}]
          ;; Mock auxiliary LLM to return a proposed skill
          mock-reviewer-response (json/generate-string 
                                   {:proposed_skills 
                                    [{:action "create" 
                                      :name "check-open-ports" 
                                      :description "Check open network ports using netstat" 
                                      :content "Run `netstat -tuln` to see active listening ports."}]})]
      (with-redefs [llm/call-auxiliary-llm (fn [_ _] mock-reviewer-response)
                    llm/call-fallback-llm (fn [_ _] "{\"decision\": \"VERIFY\", \"outcome_found\": true, \"reasoning\": \"Looks good\"}")]
        (let [extracted-count (reviewer/review-trajectory {} trajectory)]
          (is (= 1 extracted-count))
          (let [skills (skill/list-skills :include-drafts true)
                new-skill (first (filter #(= (:name %) "check-open-ports") (remove nil? skills)))]
            (is (not (nil? new-skill)))
            (is (= "draft" (:status new-skill)))
            (println "Skill Extraction Test: Discovery passed.")))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
