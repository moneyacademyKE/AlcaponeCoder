(ns plugins.mem0
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(defn- mem0-add [api-key user-id messages]
  (let [url "https://api.mem0.ai/v1/memories/"
        body (json/generate-string {:messages messages :user_id user-id})]
    (http/post url {:headers {"Authorization" (str "Token " api-key)
                              "Content-Type" "application/json"}
                    :body body})))

(defn init []
  {:name "mem0"
   :type :memory-provider
   :add-memory (fn [config user-id messages]
                 (let [api-key (:mem0_api_key config)]
                   (when api-key
                     (mem0-add api-key user-id messages))))})
