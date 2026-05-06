(ns check-tool-tokens
  (:require [registry]
            [system]
            [cheshire.core :as json]))

(let [system (assoc (system/create-system :session-id "token-check")
                    :allowed-tools #{"terminal" "memory"})
      tools (registry/get-definitions system)
      total-json (json/generate-string tools)
      total-tokens (Math/ceil (/ (count total-json) 3.5))]
  (println (str "Total Tool Definitions: " (count tools)))
  (println (str "Total Estimated Tokens for Tools: " total-tokens))
  (doseq [t tools]
    (let [n (get-in t [:function :name])
          s (json/generate-string t)
          tok (Math/ceil (/ (count s) 3.5))]
      (println (str " - " n ": " tok " tokens"))))
  (system/cleanup system))
