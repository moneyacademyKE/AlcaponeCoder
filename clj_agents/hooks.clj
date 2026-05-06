(ns hooks)

(defn register! [system event-type handler-fn]
  (let [h-atom (get system :hooks (atom {}))]
    (swap! h-atom update event-type (fnil conj []) handler-fn)
    system))

(defn emit! [system event-type context]
  (let [h-atom (get system :hooks (atom {}))
        fns (get @h-atom event-type)]
    (doseq [f fns]
      (try
        (f system event-type context)
        (catch Exception e
          (println (str "[HOOK-ERROR] " event-type ": " (ex-message e))))))))
