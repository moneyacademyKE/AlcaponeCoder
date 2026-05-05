(ns delegation
  (:require [registry]
            [agent]
            [cheshire.core :as json]))

(def blocked-tools #{"delegate_task" "memory"})

(defn delegate-task-tool [{:keys [goal context]}]
  (if (>= registry/*depth* 2)
    "Error: Maximum delegation depth reached."
    (let [child-state (atom {:cached-prompt nil})]
      (binding [registry/*depth* (inc registry/*depth*)]
        (let [result (agent/run-conversation nil 
                                             (str "Task: " goal "\nContext: " context) 
                                             child-state)]
          (:final-response result))))))

(registry/register!
  {:name "delegate_task"
   :handler (fn [args] (delegate-task-tool (json/parse-string args true)))
   :check_fn (fn [] (< registry/*depth* 2))
   :schema {:name "delegate_task"
            :description "Delegate a complex sub-task to a subagent with isolated context."
            :parameters {:type "object"
                         :properties {:goal {:type "string" :description "The goal for the subagent"}
                                      :context {:type "string" :description "Additional context information"}}
                         :required ["goal"]}}})
