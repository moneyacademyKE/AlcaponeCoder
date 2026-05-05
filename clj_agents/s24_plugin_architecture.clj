(ns s24-plugin-architecture
  (:require [memory-manager]
            [plugins.memory-mock :as mock]
            [config]
            [store]
            [registry]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s24: Plugin Architecture (Babashka Port) ===")
    
    ;; 1. Register multiple providers
    (memory-manager/add-provider! (mock/create-provider "honcho"))
    (memory-manager/add-provider! (mock/create-provider "mem0"))
    
    (println "\n--- Prefetching context ---")
    (println (memory-manager/prefetch-all "Who is the user?"))
    
    (println "\n--- Syncing turn ---")
    (memory-manager/sync-all "hi" "hello")
    
    (println "\n--- Handling tool call ---")
    (println (memory-manager/handle-tool "honcho_search" "test-query"))
    
    (System/exit 0)))
