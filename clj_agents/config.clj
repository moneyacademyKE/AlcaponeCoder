(ns config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]))

(def default-config
  {:model "anthropic/claude-sonnet-4"
   :agent {:max_turns 90}
   :compression {:enabled true :threshold 0.5}
   :memory {:enabled true :char_limit 2200}
   :delegation {:max_iterations 50}})

(defn get-hermes-home []
  (let [env-home (System/getenv "HERMES_HOME")
        default-home (str (System/getProperty "user.home") "/.hermes")]
    (io/file (or env-home default-home))))

(defn deep-merge [v & vs]
  (letfn [(m [v1 v2]
            (if (and (map? v1) (map? v2))
              (merge-with m v1 v2)
              v2))]
    (apply merge-with m v vs)))

(defn load-env []
  (let [env-file (io/file (get-hermes-home) ".env")]
    (when (.exists env-file)
      (let [lines (str/split-lines (slurp env-file))]
        (doseq [line lines
                :let [line (str/trim line)]
                :when (and (not (str/starts-with? line "#"))
                           (str/includes? line "="))]
          (let [[k v] (str/split line #"=" 2)]
            (System/setProperty (str/trim k) (str/trim v))))))))

(defn expand-env-vars [obj]
  (cond
    (string? obj)
    (str/replace obj #"\$\{([^}]+)\}"
                 (fn [[_ var-name]]
                   (or (System/getProperty var-name)
                       (System/getenv var-name)
                       (str "${" var-name "}"))))
    
    (map? obj)
    (into {} (for [[k v] obj] [k (expand-env-vars v)]))
    
    (vector? obj)
    (mapv expand-env-vars obj)
    
    :else obj))

(defn load-config []
  (let [home (get-hermes-home)
        _ (.mkdirs home)
        config-file (io/file home "config.yaml")
        user-config (if (.exists config-file)
                      (yaml/parse-string (slurp config-file))
                      {})]
    (expand-env-vars (deep-merge default-config user-config))))

(defn save-config [config]
  (let [home (get-hermes-home)
        _ (.mkdirs home)
        config-file (io/file home "config.yaml")]
    (spit config-file (yaml/generate-string config))))
