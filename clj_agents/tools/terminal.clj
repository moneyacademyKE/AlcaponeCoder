(ns tools.terminal
  (:require [registry]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [permissions]
            [backend]))

(defn handler [arguments]
  (let [args (json/parse-string arguments true)
        command (:command args)]
    (if-not (permissions/check-permission registry/*session-id* command)
      (json/generate-string {:error (str "Permission denied for command: " command)})
      (try
        (let [{:keys [out err]} (backend/run-bash registry/*env* command)]
          (let [output (str out err)]
            (if (empty? output)
              "(no output)"
              (subs output 0 (min (count output) 10000)))))
        (catch Exception e
          (str "(error: " (ex-message e) ")"))))))

(registry/register!
 {:name "terminal"
  :handler handler
  :schema {:type "function"
           :function {:name "terminal"
                      :description "Run a shell command and return its output."
                      :parameters {:type "object"
                                   :properties {:command {:type "string"
                                                          :description "The shell command to execute"}}
                                   :required ["command"]}}}})
