(ns plugins.manager
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce plugins (atom {}))

(defn load-plugins! []
  (let [plugin-dir (io/file "clj_agents/plugins")]
    (when (.exists plugin-dir)
      (doseq [f (.listFiles plugin-dir)
              :when (and (.isFile f) (str/ends-with? (.getName f) ".clj") (not= (.getName f) "manager.clj"))]
        (let [ns-name (str "plugins." (str/replace (.getName f) #"\.clj$" ""))]
          (try
            (require (symbol ns-name))
            (let [p-ns (find-ns (symbol ns-name))
                  init-fn (ns-resolve p-ns 'init)]
              (when init-fn
                (let [metadata (init-fn)]
                  (swap! plugins assoc (:name metadata) metadata)
                  (println (str "[PLUGIN] Loaded: " (:name metadata))))))
            (catch Exception e (println (str "Failed to load plugin " ns-name ": " (ex-message e))))))))))

(defn get-plugin [name]
  (get @plugins name))
