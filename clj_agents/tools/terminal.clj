(ns tools.terminal
  (:require [registry]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [permissions]
            [backend]))

(defn handler [system arguments]
  (let [args (json/parse-string arguments true)
        command (:command args)]
    (if-not (permissions/check-permission system command)
      (json/generate-string {:status "error" :message (str "Permission denied for command: " command)})
      (try
        (let [res (backend/run-bash (:env system) command)
              stdout (:out res)
              stderr (:err res)
              exit-code (:exit res)
              truncated-stdout (if (> (count stdout) 5000) (str (subs stdout 0 5000) "\n... (truncated)") stdout)
              truncated-stderr (if (> (count stderr) 2000) (str (subs stderr 0 2000) "\n... (truncated)") stderr)]
          (json/generate-string 
           {:exit_code exit-code
            :stdout truncated-stdout
            :stderr truncated-stderr
            :metadata {:type (:type res) :duration (:duration res)}}))
        (catch Exception e
          (json/generate-string {:status "error" :message (ex-message e)}))))))

(defn register-tools [system]
  (registry/register
    system
    {:name "terminal"
     :handler handler
     :schema {:type "function"
              :function {:name "terminal"
                         :description "Run a shell command and return its output."
                         :parameters {:type "object"
                                      :properties {:command {:type "string"
                                                             :description "The shell command to execute"}}
                                      :required ["command"]}}}}))

(defn register-tools! [system] (register-tools system)) ;; legacy alias
