(ns s18-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [registry]
             [tools.multimedia]
             [cheshire.core :as json]
             [clojure.java.io :as io]
             [clojure.string :as str]))

(deftest test-multimedia
  (testing "Vision Analyze"
    (let [res-json (registry/dispatch "vision_analyze" "{\"image_url\": \"cat.jpg\", \"question\": \"Is it a cat?\"}")
          res (:result (json/parse-string res-json true))]
      (is (str/includes? res "landscape")) ;; Our mock returns landscape
      (is (str/includes? res "Is it a cat?"))))

  (testing "Text to Speech"
    (let [res-json (registry/dispatch "text_to_speech" "{\"text\": \"hello\"}")
          res (:result (json/parse-string res-json true))]
      (is (str/starts-with? res "MEDIA:"))
      (let [path (subs res 6)]
        (is (.exists (io/file path)))
        (is (= "hello" (slurp path)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
