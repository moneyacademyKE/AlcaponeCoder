(ns test-docker
  (:require [backend]
            [clojure.string :as str]
            [babashka.process :refer [shell]]))

(try
  (println "Testing Docker Backend...")
  (let [env (backend/create-env :docker :image "bash:latest")]
    (println "Running 'whoami' in Docker...")
    (let [res1 (backend/run-bash env "whoami")]
      (println (str "Output: " (:out res1)))
      (if (str/includes? (:out res1) "root")
        (println "✅ Docker Success")
        (println "❌ Docker Failed (Unexpected output)")))
    (println "Cleaning up...")
    (backend/cleanup env))
  (catch Exception e
    (println "❌ Docker Error:" (ex-message e))))
