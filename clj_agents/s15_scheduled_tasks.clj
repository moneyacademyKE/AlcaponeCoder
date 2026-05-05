(ns s15-scheduled-tasks
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [registry]
            [store]
            [agent]
            [cron]
            [config]
            [gateway]
            [adapters.mock :as mock]
            [tools.terminal]))

;; --- Cron Tool ---
(defn cron-tool [{:keys [action schedule prompt job_id]}]
  (case action
    "create"
    (let [[next-fire one-shot] (cron/parse-schedule schedule)
          job (cron/map->CronJob
               {:job-id (str (java.util.UUID/randomUUID))
                :schedule schedule
                :prompt prompt
                :session-key registry/*session-id*
                :next-fire next-fire
                :one-shot one-shot})]
      (cron/add-job! job)
      (str "Job created: " (:job-id job)))
    
    "list"
    (let [jobs (vals @cron/job-store)]
      (if (empty? jobs)
        "No jobs."
        (str/join "\n" (for [j jobs] (str (:job-id j) ": " (:schedule j) " -> " (:prompt j))))))
    
    "delete"
    (do (cron/remove-job! job_id)
        (str "Job deleted: " job_id))))

(registry/register!
  {:name "cron"
   :handler (fn [args] (cron-tool (json/parse-string args true)))
   :schema {:name "cron"
            :description "Schedule a task to run in the future."
            :parameters {:type "object"
                         :properties {:action {:type "string" :enum ["create" "list" "delete"]}
                                      :schedule {:type "string" :description "e.g. 30m, every 1h"}
                                      :prompt {:type "string" :description "What to do"}
                                      :job_id {:type "string"}}
                         :required ["action"]}}})

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (cron/load-jobs!)
    (println "=== s15: Scheduled Tasks (Babashka Port) ===")
    
    (let [runner (gateway/create-gateway-runner runtime-config)
          wecom (mock/create-mock-adapter "wecom")]
      (gateway/register-adapter! runner "wecom" wecom)
      
      ;; Start scheduler
      (cron/start-scheduler! 
        (fn [job]
          (println (str "\n[CRON FIRE] " (:job-id job)))
          (gateway/handle-message runner (gateway/map->MessageEvent
                                          {:message-id (str "cron-" (:job-id job))
                                           :text (:prompt job)
                                           :source (gateway/map->SessionSource
                                                    {:platform "cron"
                                                     :chat-id (:session-key job)
                                                     :chat-type "dm"
                                                     :user-id "system"})
                                           :message-type "text"}))))
      
      (println "Running... (Use cron tool to schedule tasks)")
      ;; For demo, we'll just wait
      (Thread/sleep 60000)
      (System/exit 0))))
