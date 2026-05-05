(ns plugins.memory-mock)

(defn create-provider [name-id]
  {:name name-id
   :prefetch (fn [q] (str "[" name-id "] Prefetched context for " q))
   :sync (fn [u a] (println (str "[" name-id "] Synced turn.")))
   :tools [{:name (str name-id "_search")}]
   :handler (fn [t a] (str "[" name-id "] Results for " a))})
