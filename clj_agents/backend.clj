(ns backend
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

(defprotocol IEnvironment
  (run-bash [this cmd-string])
  (cleanup [this]))

(defn wrap-command [env-id command]
  (let [snap-path (format "/tmp/hermes-snap-%s.sh" env-id)
        cwd-path (format "/tmp/hermes-cwd-%s.txt" env-id)
        cwd (if (.exists (io/file cwd-path)) (str/trim (slurp cwd-path)) ".")]
    (format "source %s 2>/dev/null; cd %s 2>/dev/null; %s; _exit=$?; export -p > %s 2>/dev/null; pwd -P > %s 2>/dev/null; exit $_exit"
            snap-path cwd command snap-path cwd-path)))

(defrecord LocalEnvironment [id]
  IEnvironment
  (run-bash [this command]
    (let [wrapped (wrap-command id command)
          ;; Filter secrets
          env (into {} (filter (fn [[k v]] (not (str/includes? (str/lower-case k) "api_key"))) 
                               (System/getenv)))]
      (try
        (let [res (shell {:out :string :err :string :continue true :env env} "bash" "-c" wrapped)]
          {:out (:out res) :err (:err res) :exit (:exit res)})
        (catch Exception e
          {:out "" :err (.getMessage e) :exit 1}))))
  (cleanup [this]
    (let [snap-path (format "/tmp/hermes-snap-%s.sh" id)
          cwd-path (format "/tmp/hermes-cwd-%s.txt" id)]
      (io/delete-file snap-path true)
      (io/delete-file cwd-path true))))

(defn create-local-env []
  (->LocalEnvironment (str (java.util.UUID/randomUUID))))
