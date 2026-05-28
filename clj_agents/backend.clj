(ns backend
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

(defprotocol IEnvironment
  (run-bash [this cmd-string])
  (cleanup [this]))

(defn wrap-command [env-id command]
  (let [snap-path (format "/tmp/hermes-snap-%s.sh" env-id)
        cwd-path (format "/tmp/hermes-cwd-%s.txt" env-id)]
    (format "source %s 2>/dev/null; [ -f %s ] && cd $(cat %s) 2>/dev/null;\n%s\n_exit=$?; export -p > %s 2>/dev/null; pwd -P > %s 2>/dev/null; exit $_exit"
            snap-path cwd-path cwd-path command snap-path cwd-path)))

(defrecord LocalEnvironment [id]
  IEnvironment
  (run-bash [this command]
    (let [wrapped (wrap-command id command)
          ;; Filter secrets
          env (into {} (filter (fn [[k v]] (not (str/includes? (str/lower-case k) "api_key"))) 
                               (System/getenv)))]
      (try
        (let [start-time (System/currentTimeMillis)
              res (shell {:out :string :err :string :continue true :env env} "bash" "-c" wrapped)
              end-time (System/currentTimeMillis)]
          {:out (:out res) :err (:err res) :exit (:exit res) :type :local :duration (- end-time start-time)})
        (catch Exception e
          {:out "" :err (ex-message e) :exit 1}))))
  (cleanup [this]
    (let [snap-path (format "/tmp/hermes-snap-%s.sh" id)
          cwd-path (format "/tmp/hermes-cwd-%s.txt" id)]
      (io/delete-file snap-path true)
      (io/delete-file cwd-path true))))

(defrecord DockerEnvironment [id image container-id]
  IEnvironment
  (run-bash [this command]
    (when-not @container-id
      (let [name (str "hermes-" id)
            res (shell {:out :string :err :string} 
                       "docker" "run" "-d" "--name" name 
                       "--cap-drop" "ALL" "--pids-limit" "256" "--memory" "512m"
                       image "sleep" "infinity")]
        (reset! container-id (str/trim (:out res)))))
    (let [wrapped (wrap-command id command)]
      (try
        (let [start-time (System/currentTimeMillis)
              res (shell {:out :string :err :string :continue true}
                         "docker" "exec" @container-id "bash" "-c" wrapped)
              end-time (System/currentTimeMillis)]
          {:out (:out res) :err (:err res) :exit (:exit res) :type :docker :duration (- end-time start-time)})
        (catch Exception e
          {:out "" :err (ex-message e) :exit 1}))))
  (cleanup [this]
    (when @container-id
      (shell "docker" "rm" "-f" @container-id)
      (reset! container-id nil))))

(defrecord SSHEnvironment [id host user key-path control-path]
  IEnvironment
  (run-bash [this command]
    (let [wrapped (wrap-command id command)
          ssh-args (cond-> ["ssh" "-o" "ControlMaster=auto" "-o" (str "ControlPath=" control-path)
                            "-o" "ControlPersist=300" "-o" "BatchMode=yes"]
                     key-path (conj "-i" key-path))]
      (try
        (let [start-time (System/currentTimeMillis)
              res (shell {:out :string :err :string :continue true}
                         (vec (concat ssh-args [(str user "@" host) (str "bash -c " (pr-str wrapped)) ])))
              end-time (System/currentTimeMillis)]
          {:out (:out res) :err (:err res) :exit (:exit res) :type :ssh :duration (- end-time start-time)})
        (catch Exception e
          {:out "" :err (ex-message e) :exit 1}))))
  (cleanup [this]
    (shell "ssh" "-O" "exit" "-o" (str "ControlPath=" control-path) (str user "@" host))))

(defn create-env [type & {:keys [image host user key-path] :or {image "python:3.11-slim"}}]
  (let [id (str (java.util.UUID/randomUUID))]
    (case type
      :local (->LocalEnvironment id)
      :docker (->DockerEnvironment id image (atom nil))
      :ssh (->SSHEnvironment id host user key-path (format "/tmp/hermes-ssh-%s.sock" id)))))

(defn create-local-env []
  (create-env :local))
