(ns registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [registry]))

(deftest test-pure-registration
  (testing "Registration should not use atoms and should return a new system map"
    (let [system {}
          tool {:name "test-tool" :handler (fn [_ _] "ok") :schema {}}
          new-system (registry/register system tool)]
      (is (map? new-system))
      (is (not (instance? clojure.lang.Atom (:registry new-system))))
      (is (= tool (get-in new-system [:registry "test-tool"])))
      (is (= 1 (count (registry/get-definitions new-system)))))))
