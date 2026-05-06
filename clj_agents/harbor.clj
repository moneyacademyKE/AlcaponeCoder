(ns harbor
  (:require [config]
            [store]
            [agent]
            [system]
            [cheshire.core :as json]
            [logger]))

(defn -main []
  (try
    (logger/info {} "harbor_start" {:instruction (System/getenv "HARBOR_INSTRUCTION")})
    (config/load-env)
    (let [cfg (config/load-config)
          model (System/getenv "HARBOR_MODEL")
          cfg (if model (assoc-in cfg [:models :primary] model) cfg)]
      (store/init-db!)
      (let [sys (system/create-system :config cfg)
            result (agent/run-conversation sys (System/getenv "HARBOR_INSTRUCTION") (:state sys))]
        (if (= :error (:status result))
          (do
            (logger/error sys "harbor_agent_error" {:reason (:reason result) :message (:message result)})
            (println (json/generate-string {:status "error" :error result}))
            (System/exit 0)) ;; Exit 0 to prevent NonZeroAgentExitCodeError, error is in logs/output
          (do
            (logger/info sys "harbor_success" {:response (:final-response result)})
            (println (json/generate-string {:status "success" :response (:final-response result)}))
            (System/exit 0)))))
    (catch Exception e
      (let [err-msg (str "Fatal Harbor Runner Error: " (ex-message e))]
        (logger/error {} "harbor_fatal" {:message err-msg})
        (println (json/generate-string {:status "fatal" :message err-msg}))
        (System/exit 0))))) ;; Still exit 0 to allow Harbor to capture the JSON error

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
