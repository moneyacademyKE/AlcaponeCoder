(ns system
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [backend]
            [hooks]
            [registry]
            [memory]
            [skill]
            [delegation]
            [tools.terminal]
            [tools.browser]
            [tools.system-tools]
            [tools.patch]
            [tools.multimedia]
            [tools.xml]
            [store]))

(defn validate-system [system]
  (let [{:keys [registry budget approvals cron-jobs browser-process]} system]
    (when (and registry (not (map? registry)))
      (throw (Exception. (str "System key :registry must be a Map, got: " (type registry)))))
    (when (and budget (not (number? budget)))
      (throw (Exception. (str "System key :budget must be a Number, got: " (type budget)))))
    (when (and approvals (not (map? approvals)))
      (throw (Exception. (str "System key :approvals must be a Map, got: " (type approvals)))))
    (when (and cron-jobs (not (map? cron-jobs)))
      (throw (Exception. (str "System key :cron-jobs must be a Map, got: " (type cron-jobs)))))
    system))

(defn cleanup [system]
  (println "[SYSTEM] Cleaning up resources...")
  (backend/cleanup (:env system))
  (when-let [p @(:browser-process system)]
    (println "[SYSTEM] Terminating browser daemon...")
    (.destroy p))
  (println "[SYSTEM] Cleanup complete."))

(defn create-system [& {:keys [session-id config]}]
  (let [base {:id (or session-id (str (java.util.UUID/randomUUID)))
              :config (or config {})
              :budget (get-in config [:agent :max_turns] 90)
              :depth 0
              :env (backend/create-env :local)
              :registry {}
              :hooks {}
              :approvals {}
              :cron-jobs {}
              :skill-stats {}
              :state {:turns-since-memory 0
                      :iters-since-skill 0
                      :plan "No plan established yet."}
              :browser-process (atom nil)}]
    
    ;; Thread system through all register-tools calls
    (-> base
        (memory/register-tools)
        (skill/register-tools)
        (delegation/register-tools)
        (tools.terminal/register-tools)
        (tools.browser/register-tools)
        (tools.system-tools/register-tools)
        (tools.patch/register-tools)
        (tools.multimedia/register-tools)
        (tools.xml/register-tools)
        (validate-system)
        (as-> sys
          (do (.addShutdownHook (Runtime/getRuntime) 
                                (Thread. (fn [] 
                                           (println "\n[SHUTDOWN] Process terminating...")
                                           (store/save-checkpoint! sys)
                                           (cleanup sys))))
              sys)))))
