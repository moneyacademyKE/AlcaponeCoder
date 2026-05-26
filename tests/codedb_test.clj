(ns codedb-test
  (:require [clojure.test :refer [deftest is testing]]
            [codedb]
            [prompt]
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

(deftest test-generate-codebase-map
  (testing "generate-codebase-map prints a sorted list of relative paths"
    (let [temp-dir (io/file "/tmp/hermes-codedb-map-test")
          _ (.mkdirs temp-dir)
          _ (io/delete-file (io/file temp-dir "z_file.clj") true)
          _ (io/delete-file (io/file temp-dir "a_file.clj") true)
          _ (.createNewFile (io/file temp-dir "z_file.clj"))
          _ (.createNewFile (io/file temp-dir "a_file.clj"))
          codebase-map (codedb/generate-codebase-map temp-dir)]
      (is (clojure.string/includes? codebase-map "# Codebase Map (Files & Structure)"))
      (is (clojure.string/includes? codebase-map "- a_file.clj"))
      (is (clojure.string/includes? codebase-map "- z_file.clj")))))

(deftest test-codedb-tree-tool-with-sizes
  (testing "codedb-tree-tool returns file size and paths in map list format"
    (let [temp-dir (io/file "/tmp/hermes-codedb-tree-test")
          _ (.mkdirs temp-dir)
          file-a (io/file temp-dir "a.clj")
          _ (spit file-a "12345")
          res (codedb/codedb-tree-tool {} {:root-dir (.getAbsolutePath temp-dir)})
          parsed (json/parse-string (:result res) true)]
      (is (sequential? parsed))
      (is (= "a.clj" (:path (first parsed))))
      (is (= 5 (:size (first parsed)))))))

(deftest test-codedb-hot-tool
  (testing "codedb-hot-tool returns git status and recently modified files list"
    (let [temp-dir (io/file "/tmp/hermes-codedb-hot-test")
          _ (.mkdirs temp-dir)
          file-a (io/file temp-dir "a.clj")
          _ (spit file-a "hello")
          res (codedb/codedb-hot-tool {} {:root-dir (.getAbsolutePath temp-dir)})
          parsed (json/parse-string (:result res) true)]
      (is (map? parsed))
      (is (contains? parsed :git-status))
      (is (contains? parsed :recently-modified))
      (is (= "a.clj" (:path (first (:recently-modified parsed))))))))

(deftest test-prompt-injection-contains-codebase-map
  (testing "build-system-prompt prepends the codebase map structure"
    (let [system {:depth 0}
          prompt-str (prompt/build-system-prompt system {:soul "Agent soul"
                                                         :plan "Agent plan"
                                                         :project-context "Hermes context"
                                                         :memory "Agent memory"
                                                         :skills "Agent skills"})]
      (is (clojure.string/includes? prompt-str "# Codebase Map (Files & Structure)")))))


