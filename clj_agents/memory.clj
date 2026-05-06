(ns memory
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [registry]
            [logger]))

(def entry-delimiter "$")
(def default-memory-limit 2200)
(def default-user-limit 1375)

(defn- get-memory-path [system target]
  (let [config (get-in system [:config :memory] {})
        base-dir (or (:base_dir config)
                     (io/file (System/getProperty "user.home") ".hermes" "memories"))]
    (when-not (.exists (io/as-file base-dir)) (.mkdirs (io/as-file base-dir)))
    (io/file base-dir (if (= target "user") "USER.md" "MEMORY.md"))))

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

(defn load-memory [system target]
  (let [f (get-memory-path system target)]
    (if (.exists f)
      (parse-entries (slurp f))
      [])))

(defn save-memory [system target entries]
  (let [f (get-memory-path system target)
        config (get-in system [:config :memory] {})
        limit (if (= target "user") 
                (or (:user_limit config) default-user-limit)
                (or (:memory_limit config) default-memory-limit))
        ;; Simple FIFO truncation: if total chars > limit, drop first entry and recur
        truncated-entries (loop [current entries]
                            (if (or (empty? current)
                                    (<= (count (render-entries current)) limit))
                               current
                               (recur (rest current))))]
    (spit f (render-entries truncated-entries))))

(defn format-for-system-prompt [system target]
  (let [entries (load-memory system target)
        text (render-entries entries)
        config (get-in system [:config :memory] {})
        limit (if (= target "user") 
                (or (:user_limit config) default-user-limit)
                (or (:memory_limit config) default-memory-limit))
        header (if (= target "user") "USER PROFILE (who the user is)" "MEMORY (your personal notes)")
        percent (int (* 100 (/ (count text) limit)))]
    (if (empty? entries)
      nil
      (str "## " header " [" percent "% -- " (count text) "/" limit " chars]\n" text))))

;; Tool Implementation
(defn memory-tool [system {:keys [action content target] :or {target "memory"}}]
  (let [entries (load-memory system target)]
    (case action
      "add"
      (let [new-entries (conj (vec entries) content)]
        (save-memory system target new-entries)
        (str "Added to " target ". Current entries: " (count new-entries)))
      
      "remove"
      (let [new-entries (filterv #(not (str/includes? % content)) entries)]
        (save-memory system target new-entries)
        (str "Removed from " target ". Current entries: " (count new-entries)))
      
      "read"
      (render-entries entries)
      
      "search"
      (let [query-words (str/split (str/lower-case content) #"\s+")
            scored-entries (for [entry entries
                                :let [entry-lc (str/lower-case entry)
                                      matches (filter #(str/includes? entry-lc %) query-words)
                                      score (count matches)]
                                :when (pos? score)]
                            {:entry entry :score score})
            sorted-matches (->> scored-entries
                                (sort-by :score >)
                                (map :entry))]
        (if (seq sorted-matches)
          (render-entries sorted-matches)
          "No matching memories found."))
      
      (str "Unknown action: " action))))

(defn consolidate! [system messages call-llm-fn]
  (let [prompt "Review the following conversation history and extract any important facts, user preferences, or project state that should be saved to long-term memory. Output each fact as a single line starting with $. If nothing new, output nothing.\n\nCONVERSATION:\n"
        history-text (str/join "\n" (map #(str (:role %) ": " (:content %)) messages))
        result (call-llm-fn (str prompt history-text))]
    (when-not (str/blank? result)
      (let [new-facts (->> (str/split-lines result)
                           (filter #(str/starts-with? % "$"))
                           (map #(str/replace % #"^\$\s*" "")))]
        (when (seq new-facts)
          (let [existing (load-memory system "memory")
                updated (into existing new-facts)]
            (save-memory system "memory" updated)
            (logger/info system "memory_consolidated" {:facts (count new-facts)})))))))

(defn register-tools [system]
  (registry/register
    system
    {:name "memory"
     :handler (fn [s args] (memory-tool s (json/parse-string args true)))
     :schema {:type "function"
              :function {:name "memory"
                         :description "Manage long-term memory across sessions. Use target='user' for personal info and target='memory' for project/env info."
                         :parameters {:type "object"
                                      :properties {:action {:type "string" :enum ["add" "remove" "read" "search"]}
                                                   :content {:type "string" :description "The memory entry or search string for removal/search"}
                                                   :target {:type "string" :enum ["memory" "user"] :default "memory"}}
                                      :required ["action"]}}}}))

(defn register-tools! [system] (register-tools system)) ;; legacy alias
