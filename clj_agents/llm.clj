(ns llm
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [registry]
            [logger]))

(defn- get-model-id [system model-key]
  (or (get-in system [:config :models model-key])
      (get-in system [:config :models :primary])))

(defn call-model 
  "Low-level model caller. Takes an explicit model-id string."
  [system messages system-prompt model-id]
  (let [config (:config system)]
    (try
      (let [tools (registry/get-definitions system)
            payload (cond-> {:model model-id
                             :messages (into [{:role "system" :content system-prompt}] messages)
                             :max_tokens (get-in config [:agent :max_tokens] 4096)}
                      (seq tools) (assoc :tools tools))
            body (json/generate-string payload)
            response (http/post (str (:base-url config) "/chat/completions")
                                {:headers {"Authorization" (str "Bearer " (:api-key config))
                                           "Content-Type" "application/json"}
                                 :body body
                                 :timeout 180000
                                 :throw false})]
        (if (= 200 (:status response))
          {:status :ok :data (json/parse-string (:body response) true)}
          (let [err-msg (str "API error: " (:status response) " - " (:body response) 
                             " (Model: " model-id ", URL: " (:base-url config) ")")]
            {:status :error :code (:status response) :message err-msg})))
      (catch Exception e
        {:status :error :code 503 :message (str "HTTP Request failed: " (ex-message e))}))))

(defn call 
  "High-level caller. Supports :primary or :fallback keys."
  [system messages system-prompt model-key]
  (let [model-id (get-model-id system model-key)
        _ (logger/info system :llm_call {:model model-id :role model-key})]
    (call-model system messages system-prompt model-id)))

(defn call-with-fallback
  "Attempts primary model, falls back to secondary on error."
  [system messages system-prompt]
  (let [primary-res (call system messages system-prompt :primary)]
    (if (= :ok (:status primary-res))
      primary-res
      (do
        (logger/warn system :llm_fallback {:reason (:message primary-res)})
        (call system messages system-prompt :fallback)))))

;; Legacy compatibility shims (to be removed once all callers are updated)
(defn call-auxiliary-llm [system prompt-text]
  (let [model-key (if (get-in system [:config :models :auxiliary]) :auxiliary :fallback)
        res (call system [{:role "user" :content prompt-text}] "You are a helpful assistant." model-key)]
    (if (= :ok (:status res))
      (get-in res [:data :choices 0 :message :content])
      "Failed to call auxiliary LLM.")))

(defn call-fallback-llm [system prompt-text]
  (let [model-key (if (get-in system [:config :models :auxiliary]) :auxiliary :fallback)
        res (call system [{:role "user" :content prompt-text}] "You are a Judge." model-key)]
    (if (= :ok (:status res))
      (get-in res [:data :choices 0 :message :content])
      "Failed to call fallback LLM.")))
