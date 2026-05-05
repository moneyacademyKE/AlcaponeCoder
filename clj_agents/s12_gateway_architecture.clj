(ns s12-gateway-architecture
  (:require [gateway]
            [adapters.mock :as mock]
            [config]
            [store]
            [clojure.string :as str]))

;; ===========================================================================
;; Entry Point
;; ===========================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (config/load-env)
  (let [runtime-config (config/load-config)]
    (store/init-db!)
    (println "=== s12: Gateway Architecture (Babashka Port) ===")
    
    (let [runner (gateway/create-gateway-runner runtime-config)
          wecom (mock/create-mock-adapter "wecom")
          tg (mock/create-mock-adapter "telegram")]
      
      (gateway/register-adapter! runner "wecom" wecom)
      (gateway/register-adapter! runner "telegram" tg)
      
      (println "Simulating multi-platform messages...")
      
      ;; Simulate Zhang San on WeCom
      (mock/push-message! wecom {:text "Hello from WeCom!" :chat-id "zhangsan" :chat-type "dm" :user-id "zhangsan"})
      
      ;; Simulate Li Si on Telegram
      (mock/push-message! tg {:text "Hi from Telegram!" :chat-id "lisi" :chat-type "dm" :user-id "lisi"})
      
      ;; Wait for processing
      (Thread/sleep 5000)
      (println "\nSimulation complete.")
      (System/exit 0))))
