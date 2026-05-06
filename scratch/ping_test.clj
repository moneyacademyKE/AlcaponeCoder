(require '[config] '[llm] '[system])

(let [cfg (config/load-config)
      sys (system/create-system :config cfg)
      model-id (get-in sys [:config :models :primary])]
  (println "Pinging model:" model-id)
  (if (llm/ping-model sys model-id)
    (println "SUCCESS: Model is alive.")
    (do (println "FAILURE: Model is unreachable or returned error.")
        (System/exit 1))))
