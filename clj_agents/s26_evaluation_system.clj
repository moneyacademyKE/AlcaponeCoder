(ns s26-evaluation-system
  (:require [eval]
            [skill]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (println "=== s26: Evaluation System (Babashka Port) ===")
  
  (let [skill-text "# Test Skill\n1. Use pip install -e."
        task "Help me install a python project"
        output "Run pip install -e ."
        expected "Should use editable install"
        
        constraints (eval/validate-constraints skill-text)
        score (eval/llm-judge skill-text task output expected)]
    
    (println "Constraints Check:" constraints)
    (println "Fitness Score:" score)
    
    (if (:valid? constraints)
      (println "Status: READY FOR EVOLUTION")
      (println "Status: FAILED CONSTRAINTS"))
    
    (System/exit 0)))
