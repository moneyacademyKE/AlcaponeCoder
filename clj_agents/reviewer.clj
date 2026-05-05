(ns reviewer
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [skill]
            [registry]
            [llm]))

(def extract-skills-prompt
  "You are a Senior Reviewer Agent. Your job is to analyze a conversation trajectory and extract reusable 'Skills'.
   A Skill is a specific methodology, command sequence, or environment workaround that was successful.
   
   RULES:
   1. Look for multi-step terminal command sequences that solved a problem.
   2. Look for specific configurations or file edits that fixed an error.
   3. Ignore one-off conversation chatter.
   4. Output your findings as a JSON list of skill objects for the `skill_manage` tool.
   
   JSON Format:
   {
     \"proposed_skills\": [
       {
         \"action\": \"create\",
         \"name\": \"skill-name-kebab-case\",
         \"description\": \"What this skill does\",
         \"content\": \"Full SKILL.md body. Use standard documentation format.\"
       }
     ]
   }
   
   If no new skills are found, return an empty list.")

(def judge-skill-prompt
  "You are an RL-as-Judge Agent. Your goal is to verify if a proposed Skill is safe, accurate, and reusable.
   
   EVALUATION CRITERIA:
   1. EMPIRICAL OUTCOME (PRIORITY): Did the trajectory end with a successful test result? (Look for 'Score: 1.0', 'All tests passed', 'Success').
   2. Accuracy: Do the commands/instructions correctly solve the problem described?
   3. Safety: Does the skill avoid destructive actions without justification?
   4. Reusability: Is the skill generic enough to be used in other similar contexts?
   
   INPUT:
   - Proposed Skill JSON
   - Conversation Trajectory (including tool outputs and test results)
   
   OUTPUT:
   Return a JSON object:
   {
     \"decision\": \"VERIFY\" or \"REJECT\",
     \"outcome_found\": true/false,
     \"reasoning\": \"Brief explanation of your decision, noting if success was empirically verified.\"
   }")

(defn judge-skill [skill messages]
  (println (str "[JUDGE] Auditing skill: " (:name skill)))
  (let [prompt (str judge-skill-prompt "\n\nPROPOSED SKILL:\n" (json/generate-string skill) 
                    "\n\nTRAJECTORY:\n" (str/join "\n" (map :content (take-last 20 messages))))
        response-str (llm/call-fallback-llm prompt)
        decision (try (json/parse-string response-str true) (catch Exception _ {:decision "REJECT" :reasoning "Failed to parse judge response"}))]
    (if (= "VERIFY" (:decision decision))
      (do 
        (println (str "[JUDGE] ✅ VERIFIED: " (:name skill) " (Outcome Found: " (:outcome_found decision) ") - " (:reasoning decision)))
        (skill/skill-manage-tool {:action "edit" :name (:name skill) :status "verified" :content (:content skill)}))
      (println (str "[JUDGE] ❌ REJECTED: " (:name skill) " - " (:reasoning decision))))))

(defn review-trajectory [messages]
  (println "[REVIEWER] Analyzing trajectory for new skills...")
  (let [history-text (str/join "\n" (map #(str (:role %) ": " (:content %) (when (:tool_calls %) (str "\n[tools] " (json/generate-string (:tool_calls %))))) messages))
        full-prompt (str extract-skills-prompt "\n\nCONVERSATION TRAJECTORY:\n" history-text)
        response-str (llm/call-auxiliary-llm full-prompt)]
    
    ;; Post-Implementation Usage Audit
    (let [viewed-skills (->> messages
                             (mapcat :tool_calls)
                             (filter #(= "skill_view" (:name %)))
                             (map #(get-in % [:arguments :name]))
                             (distinct))
          success? (re-find #"(?i)Score: 1.0|Success|Passed" history-text)]
      (when (and success? (seq viewed-skills))
        (println (str "[REVIEWER] Task successful! Updating " (count viewed-skills) " used skills with success hits."))
        (doseq [s viewed-skills]
          (skill/track-usage! s true))))

    (try
      (let [parsed (json/parse-string response-str true)]
        (doseq [s (:proposed_skills parsed)]
          (println (str "[REVIEWER] Proposing new skill: " (:name s)))
          (skill/skill-manage-tool (assoc s :status "draft"))
          ;; Autonomous RL-as-Judge Verification
          (judge-skill s messages))
        (count (:proposed_skills parsed)))
      (catch Exception e
        (println "[REVIEWER] Failed to parse proposed skills:" (ex-message e))
        0))))
