(require '[clj-yaml.core :as yaml]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

;; Mocking the config namespace for testing
(load-file "clj_agents/config.clj")

(println "=== Config Test ===")
(let [cfg (config/load-config)]
  (println (str "API Key set: " (not (str/starts-with? (:api-key cfg) "${"))))
  (if (str/starts-with? (:api-key cfg) "${")
    (println (str "Key value: " (:api-key cfg)))
    (println (str "Key starts with: " (subs (:api-key cfg) 0 10) "..."))))
