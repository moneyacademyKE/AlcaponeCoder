(ns memory
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [registry]))

(def entry-delimiter "$")
(def memory-limit 2200)
(def user-limit 1375)

(defn- get-memory-path [target]
  (let [home (System/getProperty "user.home")
        dir (io/file home ".hermes" "memories")]
    (when-not (.exists dir) (.mkdirs dir))
    (io/file dir (if (= target "user") "USER.md" "MEMORY.md"))))

(defn parse-entries [text]
  (if (str/blank? text)
    []
    (->> (str/split text (re-pattern (str "\\" entry-delimiter)))
         (map str/trim)
         (remove str/blank?))))

(defn render-entries [entries]
  (if (empty? entries)
    ""
    (str/join "\n" (map #(str entry-delimiter " " %) entries))))

(defn load-memory [target]
  (let [f (get-memory-path target)]
    (if (.exists f)
      (parse-entries (slurp f))
      [])))

(defn save-memory [target entries]
  (let [f (get-memory-path target)]
    (spit f (render-entries entries))))

(defn format-for-system-prompt [target]
  (let [entries (load-memory target)
        text (render-entries entries)
        limit (if (= target "user") user-limit memory-limit)
        header (if (= target "user") "USER PROFILE (who the user is)" "MEMORY (your personal notes)")
        percent (int (* 100 (/ (count text) limit)))]
    (if (empty? entries)
      nil
      (str "## " header " [" percent "% -- " (count text) "/" limit " chars]\n" text))))

;; Tool Implementation
(defn memory-tool [{:keys [action content target] :or {target "memory"}}]
  (let [entries (load-memory target)]
    (case action
      "add"
      (let [new-entries (conj (vec entries) content)]
        (save-memory target new-entries)
        (str "Added to " target ". Current entries: " (count new-entries)))
      
      "remove"
      (let [new-entries (filterv #(not (str/includes? % content)) entries)]
        (save-memory target new-entries)
        (str "Removed from " target ". Current entries: " (count new-entries)))
      
      "read"
      (render-entries entries)
      
      (str "Unknown action: " action))))

(defn consolidate! [messages call-llm-fn]
  (let [prompt "Review the following conversation history and extract any important facts, user preferences, or project state that should be saved to long-term memory. Output each fact as a single line starting with $. If nothing new, output nothing.\n\nCONVERSATION:\n"
        history-text (str/join "\n" (map #(str (:role %) ": " (:content %)) messages))
        result (call-llm-fn (str prompt history-text))]
    (when-not (str/blank? result)
      (let [new-facts (->> (str/split-lines result)
                           (filter #(str/starts-with? % "$"))
                           (map #(str/replace % #"^\$\s*" "")))]
        (when (seq new-facts)
          (let [existing (load-memory "memory")
                updated (into existing new-facts)]
            (save-memory "memory" updated)
            (println (str "[MEMORY] Consolidated " (count new-facts) " new facts."))))))))

;; Register the tool
(registry/register!
  {:name "memory"
   :handler (fn [args] (memory-tool (json/parse-string args true)))
   :schema {:type "function"
            :function {:name "memory"
                       :description "Manage long-term memory across sessions. Use target='user' for personal info and target='memory' for project/env info."
                       :parameters {:type "object"
                                    :properties {:action {:type "string" :enum ["add" "remove" "read"]}
                                                 :content {:type "string" :description "The memory entry or search string for removal"}
                                                 :target {:type "string" :enum ["memory" "user"] :default "memory"}}
                                    :required ["action"]}}}})
