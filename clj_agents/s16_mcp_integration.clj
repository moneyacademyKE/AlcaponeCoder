(ns s16-mcp-integration
  (:require [mcp]
            [registry]
            [config]
            [store]
            [agent]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s16: MCP Integration (Babashka Port) ===")
    
    (println "Connecting to Mock MCP Server...")
    (let [mock-server (mcp/connect-stdio "mock" "bb" ["/Users/moe/.gemini/antigravity/brain/a2949aac-66a6-4c0a-a1d6-7f91b70b8db0/scratch/mock_mcp_server.clj"] {})]
      (mcp/register-mcp-tools! mock-server)
      
      (println "Registered MCP tools:")
      (doseq [t (keys @registry/tools)]
        (when (str/starts-with? t "mcp_")
          (println (str " - " t))))
      
      ;; Test call
      (println "\nTesting MCP tool call (mcp_mock_echo)...")
      (let [res (registry/dispatch "mcp_mock_echo" "{\"text\": \"Hello MCP!\"}")]
        (println (str "Result: " res)))
      
      (System/exit 0))))
