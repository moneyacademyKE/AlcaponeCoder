(ns memory-manager
  (:require [memory]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def providers (atom []))
(def tool-to-provider (atom {}))

(defn add-provider! [p]
  (swap! providers conj p)
  (doseq [t (:tools p)]
    (swap! tool-to-provider assoc (:name t) p)))

(defn prefetch-all [system query]
  (let [results (for [p @providers]
                  (try
                    (when-let [f (:prefetch p)] (f system query))
                    (catch Exception e nil)))]
    (str/join "\n" (remove nil? results))))

(defn sync-all [system user assistant]
  (doseq [p @providers]
    (try
      (when-let [f (:sync p)] (f system user assistant))
      (catch Exception e nil))))

(defn handle-tool [system name args]
  (if-let [p (get @tool-to-provider name)]
    ((:handler p) system name args)
    (str "Error: Unknown memory tool " name)))
