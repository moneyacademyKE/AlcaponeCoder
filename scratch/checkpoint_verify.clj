(ns checkpoint-verify
  (:require [store]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(defn -main []
  (let [system-id "test-checkpoint-123"
        test-system {:id system-id
                    :budget 88
                    :state {:plan "Verify checkpointing" :turns-since-memory 5}
                    :registry {:some "tool"} ;; Should be dissoc'd
                    :browser-process (atom nil)}] ;; Should be dissoc'd
    
    (println "Saving checkpoint for" system-id)
    (store/save-checkpoint! test-system)
    
    (let [loaded (store/load-checkpoint system-id)]
      (if loaded
        (do
          (println "Loaded checkpoint:" (json/generate-string loaded {:pretty true}))
          (if (and (= (get loaded :id) system-id)
                   (= (get loaded :budget) 88)
                   (= (get-in loaded [:state :turns-since-memory]) 5)
                   (not (contains? loaded :registry))
                   (not (contains? loaded :browser-process)))
            (println "VERIFICATION SUCCESSFUL: Checkpoint preserved logical state and excluded non-serializable atoms.")
            (println "VERIFICATION FAILED: Data mismatch.")))
        (println "VERIFICATION FAILED: Could not load checkpoint.")))))

(-main)
