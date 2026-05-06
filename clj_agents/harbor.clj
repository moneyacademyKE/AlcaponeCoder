(defn -main []
  (try
    (require '[config] '[store] '[agent] '[system] '[cheshire.core :as json] '[logger] '[cron])
    (let [json-gen (resolve 'json/generate-string)
          logger-info (resolve 'logger/info)
          logger-error (resolve 'logger/error)
          config-load-env (resolve 'config/load-env)
          config-load-config (resolve 'config/load-config)
          store-init-db! (resolve 'store/init-db!)
          system-create-system (resolve 'system/create-system)
          agent-run-conversation (resolve 'agent/run-conversation)]

      (logger-info {} "harbor_start" {:instruction (System/getenv "HARBOR_INSTRUCTION")})
      (config-load-env)
      (let [cfg (config-load-config)
            model (System/getenv "HARBOR_MODEL")
            cfg (if model (assoc-in cfg [:models :primary] model) cfg)]
        (store-init-db!)
        (let [trace-id (System/getenv "HARBOR_TRACE_ID")
              sys (system-create-system :config cfg)
              sys (if trace-id (assoc sys :trace-id trace-id) sys)
              result (agent-run-conversation sys (System/getenv "HARBOR_INSTRUCTION") (:state sys))]
          (if (= :error (:status result))
            (do
              (logger-error sys "harbor_agent_error" {:reason (:reason result) :message (:message result)})
              ;; Sanitize result: Only include plain data for JSON output
              (println (json-gen {:status "error" 
                                  :error {:reason (:reason result) 
                                          :message (:message result)}}))
              (System/exit 0))
            (do
              (logger-info sys "harbor_success" {:response (:final-response result)})
              (println (json-gen {:status "success" :response (:final-response result)}))
              (System/exit 0))))))
    (catch Exception e
      (let [err-msg (str "Fatal Harbor Runner Error: " (ex-message e))]
        (try
          (let [json-fn (or (resolve 'cheshire.core/generate-string) pr-str)]
            (println (json-fn {:status "fatal" :message err-msg})))
          (catch Exception _
            (println (str "{\"status\": \"fatal\", \"message\": \"" err-msg "\"}"))))
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
