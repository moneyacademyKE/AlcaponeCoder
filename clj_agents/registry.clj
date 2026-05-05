(ns registry
  (:require [cheshire.core :as json]
            [permissions]))

(defonce tools (atom {}))
(def ^:dynamic *session-id* nil)
(def ^:dynamic *budget* nil)
(def ^:dynamic *config* nil)
(def ^:dynamic *depth* 0)
(def ^:dynamic *env* nil)

(defn register! [{:keys [name handler schema is_async check_fn] :as tool-entry}]
  (swap! tools assoc name tool-entry)
  (println (str "  [registry] Registered tool: " name)))

(defn get-definitions []
  (->> @tools
       vals
       (filter #(if (:check_fn %) ((:check_fn %)) true))
       (map :schema)))

(defn dispatch [name arguments]
  (let [entry (get @tools name)]
    (if-not entry
      (json/generate-string {:status "error" :message (str "Unknown tool: " name)})
      (let [handler (:handler entry)
            session-id *session-id*]
        (if (and session-id (not (permissions/check-permission session-id (str name " " arguments))))
          (json/generate-string {:status "error" :message "Permission denied by user."})
          (try
            (handler arguments)
            (catch Exception e
              (json/generate-string {:status "error" :message (str "Error executing tool '" name "': " (ex-message e))}))))))))
