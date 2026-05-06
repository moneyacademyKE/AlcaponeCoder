(ns s04-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [prompt]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deftest test-prompt-builder
  (testing "Priority chain: HERMES.md over AGENTS.md"
    (spit "HERMES.md" "Hermes context")
    (spit "AGENTS.md" "Agents context")
    (let [ctx (prompt/load-project-context ".")]
      (is (= "Hermes context" ctx)))
    (io/delete-file "HERMES.md")
    (let [ctx (prompt/load-project-context ".")]
      (is (= "Agents context" ctx)))
    (io/delete-file "AGENTS.md"))

  (testing "Prompt assembly layers"
    (let [sys {:depth 0}
          p (prompt/build-system-prompt sys {:soul "Identity"
                                             :memory "Preferences"
                                             :project-context "Rules"})]
      (is (str/includes? p "Identity"))
      (is (str/includes? p "# Memory\nPreferences"))
      (is (str/includes? p "# Project Context\nRules"))
      (is (str/includes? p "Current time:"))))

  (testing "Truncation"
    ;; load-project-context uses 30000 char limit — create 31000 to trigger truncation
    (let [long-str (str/join "" (repeat 31000 "a"))]
      (spit "HERMES.md" long-str)
      (let [ctx (prompt/load-project-context ".")]
        (is (<= (count ctx) 30100)) ;; 30000 + "\n\n[...truncated...]" overhead
        (is (str/includes? ctx "[...truncated...]")))
      (io/delete-file "HERMES.md"))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
