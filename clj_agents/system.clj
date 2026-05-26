(ns system
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
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
            [codedb]
            [store]))

(s/def ::id string?)
(s/def ::config map?)
(s/def ::budget number?)
(s/def ::depth integer?)
(s/def ::env any?)
(s/def ::registry map?)
(s/def ::hooks map?)
(s/def ::approvals map?)
(s/def ::cron-jobs map?)
(s/def ::skill-stats map?)
(s/def ::state map?)
(s/def ::browser-process (fn [x] (or (nil? x) (instance? clojure.lang.IDeref x))))

(s/def ::system-map
  (s/keys :req-un [::id ::config ::budget ::depth ::env ::registry ::hooks ::approvals ::cron-jobs ::skill-stats ::state ::browser-process]))

(defn validate-system [system]
  (if (s/valid? ::system-map system)
    system
    (throw (Exception. (str "System validation failed:\n" (s/explain-str ::system-map system))))))

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
        (codedb/register-tools)
        (validate-system)
        (as-> sys
          (do (.addShutdownHook (Runtime/getRuntime) 
                                (Thread. (fn [] 
                                           (println "\n[SHUTDOWN] Process terminating...")
                                           (store/save-checkpoint! sys)
                                           (cleanup sys))))
              sys)))))
