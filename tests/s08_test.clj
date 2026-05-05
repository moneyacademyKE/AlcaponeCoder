(ns s08-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [skill]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(deftest test-skill-system
  (testing "Skill creation and indexing"
    (skill/skill-manage-tool {:action "create" :name "test-skill-s08" :description "Test Description" :content "Skill Content"})
    (let [index (skill/get-skill-index-prompt)]
      (is (str/includes? index "test-skill-s08: Test Description")))
    
    (testing "Skill viewing"
      (let [body (skill/skill-view-tool {:name "test-skill-s08"})]
        (is (str/includes? body "Skill Content"))))
    
    (testing "Skill editing"
      (skill/skill-manage-tool {:action "edit" :name "test-skill-s08" :description "New Desc" :content "New Content"})
      (let [index (skill/get-skill-index-prompt)
            body (skill/skill-view-tool {:name "test-skill-s08"})]
        (is (str/includes? index "test-skill-s08: New Desc"))
        (is (str/includes? body "New Content"))))
    
    (testing "Skill deletion"
      (skill/skill-manage-tool {:action "delete" :name "test-skill-s08"})
      (let [index (skill/get-skill-index-prompt)]
        (is (or (nil? index) (not (str/includes? index "test-skill-s08"))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
