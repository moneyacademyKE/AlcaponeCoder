(ns llm
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [registry]))

(defn call-model [messages system-prompt config]
  (try
    (let [body (json/generate-string
                {:model (:model config)
                 :messages (into [{:role "system" :content system-prompt}] messages)
                 :max_tokens (get-in config [:agent :max_tokens] 4096)
                 :tools (registry/get-definitions)})
          response (http/post (str (:base-url config) "/chat/completions")
                              {:headers {"Authorization" (str "Bearer " (:api-key config))
                                         "Content-Type" "application/json"}
                                :body body
                                :timeout 180000
                                :throw false})]
      (if (= 200 (:status response))
        {:status :ok :data (json/parse-string (:body response) true)}
        {:status :error :code (:status response) :message (:body response)}))
    (catch Exception e
      {:status :error :code 503 :message (ex-message e)})))

(defn call-auxiliary-llm [prompt-text]
  (let [res (call-model [{:role "user" :content prompt-text}] 
                        "You are a helpful assistant." 
                        registry/*config*)]
    (if (= :ok (:status res))
      (get-in res [:data :choices 0 :message :content])
      "Failed to call auxiliary LLM.")))

(defn call-fallback-llm [prompt-text]
  (let [config (assoc registry/*config* :model (:fallback-model registry/*config*))
        res (call-model [{:role "user" :content prompt-text}] 
                        "You are a Senior Judge Agent. Your job is to verify the quality and safety of proposed AI skills." 
                        config)]
    (if (= :ok (:status res))
      (get-in res [:data :choices 0 :message :content])
      "Failed to call fallback LLM.")))
