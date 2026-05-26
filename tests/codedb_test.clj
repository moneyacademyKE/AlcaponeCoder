(ns codedb-test
  (:require [clojure.test :refer [deftest is testing]]
            [codedb]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(deftest test-file-walking
  (testing "list-project-files should exclude ignored directories"
    (let [temp-dir (io/file "/tmp/hermes-codedb-test")
          _ (.mkdirs temp-dir)
          _ (.createNewFile (io/file temp-dir "a.clj"))
          _ (.mkdirs (io/file temp-dir ".git"))
          _ (.createNewFile (io/file temp-dir ".git/config"))
          files (codedb/list-project-files temp-dir)
          paths (map #(.getName %) files)]
      (is (some #{"a.clj"} paths))
      (is (not (some #{"config"} paths))))))

(deftest test-symbol-parsing
  (testing "parse-clojure-symbols"
    (let [content "(ns my-ns) (defn add [a b] (+ a b)) (def val-1 10)"
          symbols (codedb/parse-clojure-symbols content)]
      (is (= 2 (count symbols)))
      (is (= [{:type "function" :name "add" :line 1}
              {:type "variable" :name "val-1" :line 1}]
             symbols))))

  (testing "parse-python-symbols"
    (let [content "class MyClass:\n    def my_method(self):\n        pass\ndef top_fn():\n    pass"
          symbols (codedb/parse-python-symbols content)]
      (is (= 3 (count symbols)))
      (is (= [{:type "class" :name "MyClass" :line 1}
              {:type "method" :name "my_method" :line 2}
              {:type "function" :name "top_fn" :line 4}]
             symbols))))

  (testing "parse-js-symbols"
    (let [content "function add(a, b) { return a + b; }\nconst val = 10;\nclass Foo {}"
          symbols (codedb/parse-js-symbols content)]
      (is (= 3 (count symbols)))
      (is (= [{:type "function" :name "add" :line 1}
              {:type "variable" :name "val" :line 2}
              {:type "class" :name "Foo" :line 3}]
             symbols)))))

(deftest test-search-utility
  (testing "search-files-grep finds matching lines"
    (let [temp-dir (io/file "/tmp/hermes-codedb-test")
          _ (.mkdirs temp-dir)
          file-a (io/file temp-dir "search_test.txt")
          _ (spit file-a "Hello World\nLine 2 containing test_query\nLine 3")
          matches (codedb/search-files-grep temp-dir "test_query")]
      (is (= 1 (count matches)))
      (is (= "Line 2 containing test_query" (:content (first matches))))
      (is (= 2 (:line (first matches)))))))

(deftest test-dependency-parsing
  (testing "extract-clojure-deps"
    (let [content "(ns my-ns (:require [clojure.string :as str] [another-ns]))"
          deps (codedb/extract-clojure-deps content)]
      (is (= ["clojure.string" "another-ns"] deps)))))
