(ns hooks)

(defn register! [system event-type handler-fn]
  (update system :hooks (fnil (fn [h] (update h event-type (fnil conj []) handler-fn)) {})))

(defn emit! [system event-type context]
  (let [hooks-map (get system :hooks {})
        fns (get hooks-map event-type)]
    (doseq [f fns]
      (try
        (f system event-type context)
        (catch Exception e
          (println (str "[HOOK-ERROR] " event-type ": " (ex-message e))))))))
