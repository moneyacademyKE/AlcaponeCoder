(ns registry
  (:require [cheshire.core :as json]
            [permissions]
            [logger]))

(defn register [system {:keys [name] :as tool-entry}]
  (let [new-system (update system :registry (fnil assoc {}) name tool-entry)]
    (logger/info new-system "tool_registered" {:name name})
    new-system) )

(defn register! [system tool-entry]
  ;; Backward compatibility alias for atoms (to be removed)
  (let [t-atom (get system :registry (atom {}))]
    (swap! t-atom assoc (:name tool-entry) tool-entry)
    (logger/info system "tool_registered_legacy" {:name (:name tool-entry)})
    system))

(defn get-definitions [system]
  (let [registry (get system :registry {})
        ;; Handle case where registry might still be an atom (legacy)
        data (if (instance? clojure.lang.Atom registry) @registry registry)
        allowed-tools (:allowed-tools system)]
    (->> data
         vals
         (filter (fn [tool]
                   (and (if allowed-tools (contains? allowed-tools (:name tool)) true)
                        (if (:check_fn tool) ((:check_fn tool) system) true))))
         (map :schema))))

(defn dispatch [system name arguments]
  (let [registry (get system :registry {})
        data (if (instance? clojure.lang.Atom registry) @registry registry)
        entry (get data name)]
    (if-not entry
      (json/generate-string {:status "error" :message (str "Unknown tool: " name)})
      (let [handler (:handler entry)
            session-id (:id system)]
        (if (and session-id (not (permissions/check-permission session-id (str name " " arguments))))
          (json/generate-string {:status "error" :message "Permission denied by user."})
          (try
            (handler system arguments)
            (catch Exception e
              (json/generate-string {:status "error" :message (str "Error executing tool '" name "': " (ex-message e))}))))))))
