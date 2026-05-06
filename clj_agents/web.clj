(ns web
  (:require [org.httpkit.server :as server]
            [cheshire.core :as json]
            [config]
            [store]
            [cron]))

(defn- json-response [data]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string data)})

(defn handler [system req]
  (let [{:keys [uri request-method]} req]
    (cond
      (and (= uri "/api/config") (= request-method :get))
      (json-response (config/load-config))
      
      (and (= uri "/api/jobs") (= request-method :get))
      (let [job-store (get system :cron-jobs (atom {}))]
        (json-response (vals @job-store)))
      
      :else
      {:status 404 :body "Not Found"})))

(defn start-server! [system port]
  (println (str "Starting web server on port " port "..."))
  (server/run-server (partial handler system) {:port port}))
