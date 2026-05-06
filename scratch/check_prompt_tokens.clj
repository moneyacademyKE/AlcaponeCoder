(ns check-prompt-tokens
  (:require [prompt]
            [memory]
            [skill]
            [system]))

(let [system (system/create-system :session-id "prompt-check")
      p (prompt/build-system-prompt system
                                   {:soul (prompt/load-soul)
                                    :memory (memory/format-for-system-prompt "memory")
                                    :user (memory/format-for-system-prompt "user")
                                    :skills (skill/get-skill-index-prompt)
                                    :project-context (prompt/load-project-context ".")})
      tokens (Math/ceil (/ (count p) 3.5))]
  (println (str "System Prompt Length: " (count p) " chars"))
  (println (str "System Prompt Tokens: " tokens))
  (system/cleanup system))
