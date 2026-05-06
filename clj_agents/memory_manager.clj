(ns memory-manager
  (:require [memory]
            [clojure.string :as str]
            [cheshire.core :as json]))

(defn add-provider [system p]
  (let [new-system (update system :memory-providers (fnil conj []) p)]
    (reduce (fn [sys t]
              (assoc-in sys [:memory-tool-map (:name t)] p))
            new-system
            (:tools p))))

(defn prefetch-all [system query]
  (let [providers (get system :memory-providers [])
        results (for [p providers]
                  (try
                    (when-let [f (:prefetch p)] (f system query))
                    (catch Exception e nil)))]
    (str/join "\n" (remove nil? results))))

(defn sync-all [system user assistant]
  (let [providers (get system :memory-providers [])]
    (doseq [p providers]
      (try
        (when-let [f (:sync p)] (f system user assistant))
        (catch Exception e nil)))))

(defn handle-tool [system name args]
  (if-let [p (get-in system [:memory-tool-map name])]
    ((:handler p) system name args)
    (str "Error: Unknown memory tool " name)))
