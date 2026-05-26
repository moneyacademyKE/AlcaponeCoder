(ns config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]))

(def default-config
  {:models {:primary "deepseek/deepseek-v4-flash:free"
            :fallback "deepseek/deepseek-v4-flash:free"
            :auxiliary "deepseek/deepseek-v4-flash:free"}
   :base-url "https://openrouter.ai/api/v1"
   :api-key "${OPENAI_API_KEY}"
   :agent {:max_turns 90 :max_tokens 4096 :retry_limit 10}
   :compression {:enabled true :threshold_chars 25000}
   :memory {:enabled true :char_limit 8000}})

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
                   (let [v (or (System/getProperty var-name)
                               (System/getenv var-name))]
                     (if v
                       v
                       (do (when (contains? #{"OPENAI_API_KEY" "OPENROUTER_API_KEY"} var-name)
                             (binding [*out* *err*]
                               (println (str "WARNING: Environment variable " var-name " is not set!"))))
                           (str "${" var-name "}"))))))
    
    (map? obj)
    (into {} (for [[k v] obj] [k (expand-env-vars v)]))
    
    (vector? obj)
    (mapv expand-env-vars obj)
    
    :else obj))

(defn load-config []
  (load-env)
  (let [home (get-hermes-home)
        _ (.mkdirs home)
        config-file (io/file home "config.yaml")
        raw-user-config (if (.exists config-file)
                          (yaml/parse-string (slurp config-file))
                          {})
        ;; Backward compatibility: map legacy keys into nested :models
        user-config (cond-> raw-user-config
                      (:model raw-user-config)
                      (assoc-in [:models :primary] (:model raw-user-config))
                      
                      (:fallback-model raw-user-config)
                      (assoc-in [:models :fallback] (:fallback-model raw-user-config))
                      
                      true (dissoc :model :fallback-model))
        expanded (expand-env-vars (deep-merge default-config user-config))]
    (if (str/starts-with? (:api-key expanded) "${")
      (let [fallback (or (System/getenv "OPENROUTER_API_KEY") 
                         (System/getProperty "OPENROUTER_API_KEY"))]
        (if fallback
          (assoc expanded :api-key fallback)
          expanded))
      expanded)))

(defn save-config [config]
  (let [home (get-hermes-home)
        _ (.mkdirs home)
        config-file (io/file home "config.yaml")]
    (spit config-file (yaml/generate-string config))))
