(ns s17-browser-automation
  (:require [registry]
            [config]
            [store]
            [agent]
            [tools.browser]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s17: Browser Automation (Babashka Port) ===")
    
    (println "Testing Browser Tools...")
    (let [res1 (registry/dispatch "browser_navigate" "{\"url\": \"https://github.com\"}")]
      (println (str "Navigate: " res1)))
    
    (let [res2 (registry/dispatch "browser_click" "{\"ref\": \"e3\"}")]
      (println (str "Click: " res2)))
    
    (let [res3 (registry/dispatch "browser_type" "{\"ref\": \"e3\", \"text\": \"hermes\"}")]
      (println (str "Type: " res3)))
    
    (System/exit 0)))
