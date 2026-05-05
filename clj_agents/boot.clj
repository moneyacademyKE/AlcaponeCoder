(ns boot
  (:require [clojure.java.io :as io]
            [agent]))

(defn run-boot-md! []
  (let [home (System/getProperty "user.home")
        boot-file (io/file home ".hermes" "BOOT.md")]
    (if (.exists boot-file)
      (do
        (println "[BOOT] Executing instructions from BOOT.md...")
        (let [instructions (slurp boot-file)
              agent-state (atom {:cached-prompt nil})]
          (agent/run-conversation "boot-session" instructions agent-state)))
      (println "[BOOT] No BOOT.md found. Skipping."))))
