(ns config-test
  (:require [clojure.test :refer [deftest is testing]]
            [config :as cfg]))

(deftest test-backward-compatibility
  (testing "Legacy model keys are correctly mapped to new nested structure"
    (let [legacy-config {:model "gemini-2.5-flash"
                         :fallback-model "claude-3-opus"}
          ;; Mock load-config behavior internally
          user-config (cond-> legacy-config
                        (:model legacy-config)
                        (assoc-in [:models :primary] (:model legacy-config))
                        (:fallback-model legacy-config)
                        (assoc-in [:models :fallback] (:fallback-model legacy-config))
                        true (dissoc :model :fallback-model))
          merged (cfg/deep-merge cfg/default-config user-config)]
      
      (is (= "gemini-2.5-flash" (get-in merged [:models :primary])))
      (is (= "claude-3-opus" (get-in merged [:models :fallback])))
      (is (= "deepseek/deepseek-v4-flash:free" (get-in merged [:models :auxiliary])))
      (is (nil? (:model merged)))
      (is (nil? (:fallback-model merged))))))
