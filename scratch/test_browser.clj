(ns test-browser
  (:require [tools.browser]
            [cheshire.core :as json]))

(try
  (println "Testing Real Browser Automation...")
  (let [res1 (tools.browser/navigate-handler (json/generate-string {:url "https://example.com"}))
        parsed (json/parse-string res1 true)]
    (println "Result:")
    (println (:result parsed))
    (if (clojure.string/includes? (:result parsed) "Example Domain")
      (println "✅ Browser Success")
      (println "❌ Browser Failed (Unexpected output)")))
  (catch Exception e
    (println "❌ Browser Error:" (ex-message e))))
