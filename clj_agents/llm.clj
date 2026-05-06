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

(defn- sleep [ms]
  (Thread/sleep ms))

(defn call 
  "High-level caller with exponential backoff for 429 and 5xx errors."
  [system messages system-prompt model-key]
  (let [model-id (get-model-id system model-key)
        retry-limit (get-in system [:config :agent :retry_limit] 10)]
    (loop [attempt 1
           delay 1000]
      (let [_ (logger/info system :llm_call {:model model-id :role model-key :attempt attempt})
            res (call-model system messages system-prompt model-id)]
        (if (or (= :ok (:status res))
                (>= attempt retry-limit)
                (not (contains? #{429 500 502 503 504} (:code res))))
          res
          (do
            (logger/warn system :api_retry {:attempt attempt :reason (if (= 429 (:code res)) "rate-limit" "server-error")})
            (sleep delay)
            (recur (inc attempt) (* delay 2))))))))

(defn call-with-fallback
  "Attempts primary model, falls back to secondary on error."
  [system messages system-prompt]
  (let [primary-res (call system messages system-prompt :primary)]
    (if (= :ok (:status primary-res))
      primary-res
      (do
        (logger/warn system :llm_fallback {:reason (:message primary-res)})
        (call system messages system-prompt :fallback)))))

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
