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
            [tools.multimedia]))

(defn create-system [& {:keys [session-id config]}]
  (let [plan-atom (atom "No plan established yet.")
        base {:id (or session-id (str (java.util.UUID/randomUUID)))
              :config (or config {})
              :budget (get-in config [:agent :max_turns] 90) ;; Value, not atom
              :depth 0
              :env (backend/create-env :local)
              :registry {}  ;; Plain map — not atom (pure system map pattern)
              :hooks (atom {})
              :plan-atom plan-atom ;; Mutable atom for set_plan tool cross-turn persistence
              :state {:turns-since-memory 0
                      :iters-since-skill 0
                      :plan plan-atom} ;; prompt builder dereferences this
              :browser-process (atom nil)}]

    ;; Thread system through all register-tools calls (pure — each returns enriched system)
    (-> base
        (memory/register-tools)
        (skill/register-tools)
        (delegation/register-tools)
        (tools.terminal/register-tools)
        (tools.browser/register-tools)
        (tools.system-tools/register-tools)
        (tools.patch/register-tools)
        (tools.multimedia/register-tools))))

(defn cleanup [system]
  (println "[SYSTEM] Cleaning up resources...")
  (backend/cleanup (:env system))
  (when-let [p @(:browser-process system)]
    (println "[SYSTEM] Terminating browser daemon...")
    (.destroy p))
  (println "[SYSTEM] Cleanup complete."))
