(ns s07-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [memory]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deftest test-memory-system
  (testing "Parsing and rendering"
    (let [text "$ Entry 1\n$ Entry 2"
          entries (memory/parse-entries text)]
      (is (= 2 (count entries)))
      (is (= "Entry 1" (first entries)))
      (is (= text (memory/render-entries entries)))))

  (testing "Tool: add and read"
    (memory/memory-tool {:action "add" :content "I love Clojure" :target "user"})
    (let [res (memory/memory-tool {:action "read" :target "user"})]
      (is (str/includes? res "I love Clojure")))
    
    (memory/memory-tool {:action "add" :content "Project X uses Babashka" :target "memory"})
    (let [res (memory/memory-tool {:action "read" :target "memory"})]
      (is (str/includes? res "Project X uses Babashka"))))

  (testing "Tool: remove"
    (memory/memory-tool {:action "remove" :content "love Clojure" :target "user"})
    (let [res (memory/memory-tool {:action "read" :target "user"})]
      (is (not (str/includes? res "I love Clojure")))))

  (testing "Format for system prompt"
    (let [p (memory/format-for-system-prompt "memory")]
      (is (str/includes? p "MEMORY (your personal notes)"))
      (is (str/includes? p "Project X uses Babashka")))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
