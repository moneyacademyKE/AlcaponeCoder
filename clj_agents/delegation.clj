(ns delegation
  (:require [registry]
            [agent]
            [cheshire.core :as json]))

(def blocked-tools #{"delegate_task" "memory"})

(defn delegate-task-tool [system {:keys [goal context]}]
  (if (>= (get system :depth 0) 2)
    "Error: Maximum delegation depth reached."
    (let [child-state (atom {:cached-prompt nil})
          child-system (update system :depth (fnil inc 0))]
      (let [result (agent/run-conversation child-system 
                                           (str "Task: " goal "\nContext: " context) 
                                           child-state)]
        (:final-response result)))))

(defn register-tools [system]
  (registry/register
    system
    {:name "delegate_task"
     :handler (fn [system args] (delegate-task-tool system (json/parse-string args true)))
     :check_fn (fn [system] (< (get system :depth 0) 2))
     :schema {:type "function"
              :function {:name "delegate_task"
                         :description "Delegate a complex sub-task to a subagent with isolated context."
                         :parameters {:type "object"
                                      :properties {:goal {:type "string" :description "The goal for the subagent"}
                                                   :context {:type "string" :description "Additional context information"}}
                                      :required ["goal"]}}}}))

(defn register-tools! [system] (register-tools system)) ;; legacy alias
