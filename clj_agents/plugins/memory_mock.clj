(ns plugins.memory-mock)

(defn create-provider [name-id]
  {:name name-id
   :prefetch (fn [system q] (str "[" name-id "] Prefetched context for " q))
   :sync (fn [system u a] (println (str "[" name-id "] Synced turn.")))
   :tools [{:name (str name-id "_search")}]
   :handler (fn [system t a] (str "[" name-id "] Results for " a))})
