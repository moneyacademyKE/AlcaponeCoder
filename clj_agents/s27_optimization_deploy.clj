(ns s27-optimization-deploy
  (:require [eval]
            [optimizer]
            [skill]
            [clojure.java.io :as io]))

(defn evolve-skill! [skill-name]
  (println (str "=== Evolving Skill: " skill-name " ==="))
  
  ;; 1. Load skill
  (let [skill-dir (io/file (System/getProperty "user.home") ".hermes" "skills" skill-name)
        skill-md (io/file skill-dir "SKILL.md")
        _ (when-not (.exists skill-md) (throw (Exception. "Skill not found")))
        body (slurp skill-md)]
    
    ;; 2. Validate Baseline
    (let [baseline-checks (eval/validate-constraints body)]
      (when-not (:valid? baseline-checks)
        (throw (Exception. (str "Baseline fails constraints: " (:errors baseline-checks)))))
      
      ;; 3. Optimize
      (let [result (optimizer/optimize body nil 3)
            evolved (:evolved-text result)]
        
        ;; 4. Validate Evolved
        (let [evolved-checks (eval/validate-constraints evolved)]
          (if (:valid? evolved-checks)
            (do
              (println "Optimization SUCCESS. Deploying...")
              (spit skill-md evolved)
              (println "New version saved."))
            (println (str "Optimization FAILED constraints: " (:errors evolved-checks)))))))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  ;; Ensure a skill exists to evolve
  (skill/skill-manage-tool {:action "create" :name "evolve-test" :description "test" :content "Step 1: do nothing."})
  
  (try
    (evolve-skill! "evolve-test")
    (catch Exception e (println "Error:" (.getMessage e))))
  
  (System/exit 0))
