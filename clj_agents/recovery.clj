(ns recovery
  (:require [clojure.string :as str]))

(def continue-message
  "Your response was cut off. Continue EXACTLY from where you stopped. Do not restart, do not repeat, do not summarize what came before.")

(defn classify-error [status-code error-message]
  (let [msg (str/lower-case (or error-message ""))]
    (cond
      (= status-code 429) 
      {:reason :rate-limit :retryable true :should-compress false :should-fallback false}
      
      (and (= status-code 400) (str/includes? msg "context"))
      {:reason :context-overflow :retryable true :should-compress true :should-fallback false}
      
      (or (contains? #{500 502 503} status-code)
          (str/includes? msg "closed")
          (str/includes? msg "reset")
          (str/includes? msg "timeout"))
      {:reason :server-error :retryable true :should-compress false :should-fallback false}
      
      (contains? #{401 403} status-code)
      {:reason :auth :retryable false :should-compress false :should-fallback true}
      
      (= status-code 402)
      {:reason :payment-required :retryable false :should-compress false :should-fallback true}
      
      (= status-code 404)
      {:reason :model-not-found :retryable false :should-compress false :should-fallback true}
      
      :else
      {:reason :unknown :retryable (>= status-code 500) :should-compress false :should-fallback false})))

(defn jittered-backoff [attempt]
  (let [base-delay 2000 ;; 2 seconds
        max-delay 30000 ;; 30 seconds
        delay (min (* base-delay (Math/pow 2 (dec attempt))) max-delay)
        jitter (rand (* delay 0.5))]
    (+ delay jitter)))
