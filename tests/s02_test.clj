(ns s02-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [registry]
            [cheshire.core :as json]))

;; s02-test: Updated for System Map architecture.
;; The old global-atom API (registry/tools) is replaced by system-map registry.

(defn make-test-system []
  {:id "test" :config {} :budget 10 :registry {}
   :state {:turns-since-memory 0 :iters-since-skill 0 :plan "test"}})

(deftest test-tool-registry
  (testing "Tool registration into system map"
    (let [sys (make-test-system)
          sys (registry/register sys {:name "terminal"
                                      :handler (fn [s args] "result")
                                      :schema {:type "function"
                                               :function {:name "terminal"
                                                          :parameters {:type "object" :properties {}}}}})]
      (is (contains? (:registry sys) "terminal"))))

  (testing "Get definitions returns valid schema"
    (let [sys (make-test-system)
          sys (registry/register sys {:name "terminal"
                                      :handler (fn [s args] "ok")
                                      :schema {:type "function"
                                               :function {:name "terminal"
                                                          :parameters {:type "object" :properties {}}}}})
          defs (registry/get-definitions sys)]
      (is (seq defs))
      (is (= "terminal" (get-in (first defs) [:function :name])))))

  (testing "Dispatch routes to correct handler"
    (let [sys (make-test-system)
          sys (registry/register sys {:name "echo"
                                      :handler (fn [s args]
                                                 (let [a (json/parse-string args true)]
                                                   (:command a)))})
          result (registry/dispatch sys "echo" (json/generate-string {:command "hello"}))]
      (is (= "hello" result)))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
