(ns registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [registry]
            [system]))

(deftest test-pure-registration
  (testing "Registration should not use atoms and should return a new system map"
    (let [system {}
          tool {:name "test-tool" :handler (fn [_ _] "ok") :schema {}}
          new-system (registry/register system tool)]
      (is (map? new-system))
      (is (not (instance? clojure.lang.Atom (:registry new-system))))
      (is (= "test-tool" (get-in new-system [:registry "test-tool" :name])))
      (is (= "function" (get-in new-system [:registry "test-tool" :schema :type])))
      (is (= 1 (count (registry/get-definitions new-system)))))))

(deftest test-normalization-at-registration
  (testing "Schema should be normalized at registration time, not just in get-definitions"
    (let [system {}
          flat-tool {:name "flat-tool"
                     :handler (fn [_ _] "ok")
                     :schema {:type "function"
                              :description "Flat desc"
                              :parameters {:type "object" :properties {:x {:type "string"}}}}}
          new-system (registry/register system flat-tool)
          registered-tool (get-in new-system [:registry "flat-tool"])
          schema (:schema registered-tool)]
      (is (= "function" (:type schema)))
      (is (map? (:function schema)))
      (is (= "flat-tool" (get-in schema [:function :name]))))))

(deftest test-automatic-normalization
  (testing "Flat schema should be automatically normalized"
    (let [system {}
          flat-tool {:name "flat-tool"
                     :handler (fn [_ _] "ok")
                     :schema {:type "function"
                              :description "Flat desc"
                              :parameters {:type "object" :properties {:x {:type "string"}}}}}
          new-system (registry/register system flat-tool)
          definitions (registry/get-definitions new-system)
          normalized (first definitions)]
      (is (= "function" (:type normalized)))
      (is (map? (:function normalized)))
      (is (= "flat-tool" (get-in normalized [:function :name])))
      (is (= "Flat desc" (get-in normalized [:function :description])))
      (is (= {:type "object" :properties {:x {:type "string"}}} (get-in normalized [:function :parameters]))))))

(deftest test-all-registered-schemas-compliant
  (testing "All default tools registered in system must have compliant OpenAI tool schemas"
    (let [sys (system/create-system)
          definitions (registry/get-definitions sys)]
      (is (seq definitions) "Should have registered tools")
      (doseq [schema definitions]
        (is (map? schema) "Schema must be a map")
        (is (= "function" (:type schema)) "Schema :type must be 'function'")
        (let [func (:function schema)]
          (is (map? func) "Schema must contain a nested :function map")
          (is (string? (:name func)) "Nested :function must have a string :name")
          (is (string? (:description func)) "Nested :function must have a string :description")
          (is (map? (:parameters func)) "Nested :function must have a map :parameters")
          (is (= "object" (get-in func [:parameters :type])) "Parameters :type must be 'object'"))))))

