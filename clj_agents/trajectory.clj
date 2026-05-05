(ns trajectory
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn convert-to-trajectory [messages]
  (for [msg messages]
    (let [role (:role msg)
          content (:content msg)
          from (case role
                 "system" "system"
                 "user" "human"
                 "assistant" "gpt"
                 "tool" "tool"
                 "unknown")]
      {:from from
       :value (cond
                (= role "assistant")
                (let [base (or content "")]
                  (if-let [tc (:tool_calls msg)]
                    (str base "\n" (str/join "\n" (for [t tc] 
                                                    (str "<tool_call>\n" 
                                                         (json/generate-string {:name (get-in t [:function :name])
                                                                                :arguments (json/parse-string (get-in t [:function :arguments]) true)})
                                                         "\n</tool_call>"))))
                    base))
                
                (= role "tool")
                (str "<tool_response>\n"
                     (json/generate-string {:tool_call_id (:tool_call_id msg)
                                            :content content})
                     "\n</tool_response>")
                
                :else content)})))

(defn extract-tool-stats [messages]
  (let [tool-calls (atom {})
        stats (atom {})]
    (doseq [msg messages]
      (case (:role msg)
        "assistant"
        (when-let [tc (:tool_calls msg)]
          (doseq [t tc]
            (swap! tool-calls assoc (:id t) (get-in t [:function :name]))))
        "tool"
        (let [tc-id (:tool_call_id msg)
              tool-name (get @tool-calls tc-id "unknown")]
          (swap! stats update tool-name (fnil (fn [s] (update s :count inc)) {:count 0 :success 0 :failure 0}))
          (if (str/includes? (str/lower-case (or (:content msg) "")) "error")
            (swap! stats update-in [tool-name :failure] inc)
            (swap! stats update-in [tool-name :success] inc)))
        nil))
    @stats))
