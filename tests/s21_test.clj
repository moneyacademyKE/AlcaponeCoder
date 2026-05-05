(ns s21-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [skill]
            [registry]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deftest test-skill-creation
  (testing "Skill Manage - Create"
    (let [skill-name "test-skill-123"
          res (registry/dispatch "skill_manage" 
                                 "{\"action\": \"create\", \"name\": \"test-skill-123\", \"description\": \"test desc\", \"content\": \"test body\"}")]
      (is (str/includes? res "created"))
      (let [home (System/getProperty "user.home")
            skill-md (io/file home ".hermes" "skills" skill-name "SKILL.md")]
        (is (.exists skill-md))
        (is (str/includes? (slurp skill-md) "test body")))))

  (testing "Skill Manage - Edit"
    (let [res (registry/dispatch "skill_manage" 
                                 "{\"action\": \"edit\", \"name\": \"test-skill-123\", \"description\": \"updated desc\", \"content\": \"new body\"}")]
      (is (str/includes? res "updated"))
      (let [home (System/getProperty "user.home")
            skill-md (io/file home ".hermes" "skills" "test-skill-123" "SKILL.md")]
        (is (str/includes? (slurp skill-md) "new body"))
        (is (str/includes? (slurp skill-md) "updated desc"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
