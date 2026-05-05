(ns s22-hook-system
  (:require [agent]
            [hooks]
            [boot]
            [config]
            [store]
            [registry]
            [clojure.java.io :as io]))

(defn audit-logger [event-type context]
  (println (str "[AUDIT] " event-type ": " (:name context) " with args " (:args context))))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s22: Hook System & BOOT.md (Babashka Port) ===")
    
    ;; 1. Register a hook
    (hooks/register! :pre_tool_call audit-logger)
    
    ;; 2. Simulate BOOT.md
    (let [home (System/getProperty "user.home")
          hermes-dir (io/file home ".hermes")
          boot-file (io/file hermes-dir "BOOT.md")]
      (.mkdirs hermes-dir)
      (spit boot-file "Check if /tmp exists and list its files.")
      
      (binding [registry/*config* runtime-config
                registry/*budget* (atom 100)
                registry/*depth* 1]
        
        (println "\n--- Running BOOT.md ---")
        (boot/run-boot-md!)
        
        (println "\n--- Running regular conversation (should trigger hooks) ---")
        (agent/run-conversation "s22-demo" "Read the file /etc/hostname" (atom {}))))
    
    (System/exit 0)))
