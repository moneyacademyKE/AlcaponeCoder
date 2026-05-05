(ns s24-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [memory-manager]
            [plugins.memory-mock :as mock]
            [clojure.string :as str]))

(deftest test-memory-manager
  (testing "Multi-provider prefetch"
    (reset! memory-manager/providers [])
    (memory-manager/add-provider! (mock/create-provider "p1"))
    (memory-manager/add-provider! (mock/create-provider "p2"))
    (let [res (memory-manager/prefetch-all "test")]
      (is (str/includes? res "[p1]"))
      (is (str/includes? res "[p2]"))))

  (testing "Tool routing"
    (reset! memory-manager/tool-to-provider {})
    (memory-manager/add-provider! (mock/create-provider "p1"))
    (is (str/includes? (memory-manager/handle-tool "p1_search" "q") "[p1]"))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
