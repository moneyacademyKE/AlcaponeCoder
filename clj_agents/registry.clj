(ns registry
  (:require [cheshire.core :as json]
            [permissions]
            [logger]))

(defn normalize-schema [name schema]
  (if (map? schema)
    (if-not (contains? schema :function)
      {:type (or (:type schema) "function")
       :function {:name name
                  :description (:description schema "")
                  :parameters (:parameters schema {:type "object" :properties {}})}}
      (update schema :function (fn [func] (assoc func :name (or (:name func) name)))))
    schema))

(defn register [system {:keys [name schema] :as tool-entry}]
  (let [normalized-schema (normalize-schema name schema)
        normalized-entry (assoc tool-entry :schema normalized-schema)
        new-system (update system :registry (fnil assoc {}) name normalized-entry)]
    (logger/info new-system "tool_registered" {:name name})
    new-system))

(defn register! [system tool-entry]
  ;; Legacy alias — now delegates to pure register
  (register system tool-entry))

(defn get-definitions [system]
  (let [data (get system :registry {})
        allowed-tools (:allowed-tools system)]
    (->> data
         vals
         (filter (fn [tool]
                   (and (if allowed-tools (contains? allowed-tools (:name tool)) true)
                        (if (:check_fn tool) ((:check_fn tool) system) true))))
         (map :schema))))


(defn dispatch [system name arguments]
  (let [data (get system :registry {})
        entry (get data name)]
    (if-not entry
      (json/generate-string {:status "error" :message (str "Unknown tool: " name)})
      (let [handler (:handler entry)
            [allowed? perm-update] (permissions/check-permission system (str name " " arguments))]
        (if-not allowed?
          (json/generate-string {:status "error" :message "Permission denied by user."})
          (try
            (let [res (handler system arguments)]
              (if (map? res)
                (let [handler-update (:system-update res)
                      combined-update (if (and perm-update handler-update)
                                        (comp handler-update perm-update)
                                        (or handler-update perm-update))]
                  (assoc res :system-update combined-update))
                (if perm-update
                  {:result res :system-update perm-update}
                  res)))
            (catch Exception e
              (json/generate-string {:status "error" :message (str "Error executing tool '" name "': " (ex-message e))}))))))))
