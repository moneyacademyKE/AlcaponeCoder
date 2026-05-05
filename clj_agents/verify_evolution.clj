(ns verify-evolution
  (:require [agent]
            [config]
            [store]
            [registry]
            [llm]
            [reviewer]
            [skill]
            [cheshire.core :as json]))

(defn run-verify []
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (binding [registry/*config* runtime-config
              registry/*budget* (atom 10)]
      (println "=== Verifying Evolution Loop ===")
      (let [trajectory [{:role "user" :content "I need to setup babashka."}
                        {:role "assistant" :content "Use bb init." :tool_calls [{:id "t1" :function {:name "terminal" :arguments "{\"command\": \"bb init\"}"}}]}
                        {:role "tool" :content "Project initialized."}
                        {:role "assistant" :content "Success."}]
            mock-res (json/generate-string {:proposed_skills [{:action "create" :name "bb-setup" :description "Setup babashka" :content "bb init"}]})]
        (with-redefs [llm/call-auxiliary-llm (fn [_] (println "[MOCK] Reviewer called.") mock-res)]
          (println "Triggering background review...")
          (let [f (agent/spawn-background-review! trajectory false true)]
            (println "Waiting for future...")
            (deref f)
            (println "Future finished.")
            (let [skills (skill/list-skills :include-drafts true)
                  found (first (filter #(= (:name %) "bb-setup") (remove nil? skills)))]
              (if found
                (println "SUCCESS: Skill 'bb-setup' was extracted and saved as draft.")
                (println "FAILED: Skill not found.")))))))))

(run-verify)
