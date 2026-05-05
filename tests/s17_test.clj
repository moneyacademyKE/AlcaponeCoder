(ns s17-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [registry]
            [tools.browser]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest test-browser
  (testing "Browser Navigation"
    (let [res-json (registry/dispatch "browser_navigate" "{\"url\": \"https://google.com\"}")
          res (:result (json/parse-string res-json true))]
      (is (str/includes? res "navigation \"Google\""))
      (is (str/includes? res "ref=e1"))))

  (testing "Browser Actions"
    (let [_ (registry/dispatch "browser_navigate" "{\"url\": \"https://github.com\"}")
          res-click-json (registry/dispatch "browser_click" "{\"ref\": \"e1\"}")
          res-click (:result (json/parse-string res-click-json true))
          res-type-json (registry/dispatch "browser_type" "{\"ref\": \"e3\", \"text\": \"test\"}")
          res-type (:result (json/parse-string res-type-json true))]
      (is (str/includes? res-click "Clicked element e1"))
      (is (str/includes? res-type "Typed 'test' into e3")))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
