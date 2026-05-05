(ns hooks)

(def handlers (atom {}))

(defn register! [event-type handler-fn]
  (swap! handlers update event-type (fnil conj []) handler-fn))

(defn emit! [event-type context]
  (let [fns (get @handlers event-type)]
    (doseq [f fns]
      (try
        (f event-type context)
        (catch Exception e
          (println (str "[HOOK-ERROR] " event-type ": " (.getMessage e))))))))

(defn clear! []
  (reset! handlers {}))
