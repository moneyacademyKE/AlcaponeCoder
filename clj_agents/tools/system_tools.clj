(ns tools.system-tools
  (:require [registry]
            [cheshire.core :as json]))

;; set_plan updates the immutable :state portion of the system map.
;; Since tool handlers currently don't propagate system updates back to the loop,
;; we store the plan in a session-scoped atom if available, otherwise log a warning.
;; The agent loop reads plan from (get-in system [:state :plan]).

(defn set-plan [system arguments]
  (let [args (json/parse-string arguments true)
        plan (:plan args)]
    (if-not plan
      (json/generate-string {:status "error" :message "Missing 'plan' argument"})
      ;; Return a pure data update instead of mutating an atom
      {:result (json/generate-string {:status "ok" :message "Plan updated"})
       :system-update (fn [sys] (assoc-in sys [:state :plan] plan))})))

(def plan-schema
  {:type "function"
   :function {:name "set_plan"
              :description "Update the agent's internal roadmap and status. This will be visible in the system prompt for all future turns."
              :parameters {:type "object"
                           :properties {:plan {:type "string" :description "The updated plan, including steps completed and next actions."}}
                           :required ["plan"]}}})

(defn register-tools [system]
  (registry/register system {:name "set_plan" :handler set-plan :schema plan-schema}))

(defn register-tools! [system] (register-tools system)) ;; legacy alias
