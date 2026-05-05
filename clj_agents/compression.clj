(ns compression
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [babashka.http-client :as http]))

(defn estimate-tokens [messages]
  (let [total-chars (reduce (fn [acc msg]
                              (+ acc (count (str (:content msg)))
                                 (count (str (:tool_calls msg)))))
                            0 messages)]
    (Math/ceil (/ total-chars 3.5))))

(defn prune-old-tool-results [messages keep-recent]
  (let [tool-indices (keep-indexed (fn [idx msg] (when (= (:role msg) "tool") idx)) messages)
        to-prune (if (> (count tool-indices) keep-recent)
                   (drop-last keep-recent tool-indices)
                   [])
        prune-set (set to-prune)]
    (mapv (fn [idx msg]
            (if (contains? prune-set idx)
              (assoc msg :content "[Old tool output cleared]")
              msg))
          (range (count messages))
          messages)))

(defn find-boundaries [messages protect-first tail-token-budget]
  (let [head-end (min protect-first (count messages))
        reversed-messages (reverse (subvec messages head-end))
        tail-indices (loop [msgs reversed-messages
                            acc-tokens 0
                            indices []
                            idx-from-end 0]
                       (if (empty? msgs)
                         indices
                         (let [msg (first msgs)
                               tokens (estimate-tokens [msg])]
                           (if (> (+ acc-tokens tokens) tail-token-budget)
                             indices
                             (recur (rest msgs)
                                    (+ acc-tokens tokens)
                                    (conj indices (- (count messages) 1 idx-from-end))
                                    (inc idx-from-end))))))]
    (let [tail-start (if (empty? tail-indices) (count messages) (apply min tail-indices))]
      [head-end tail-start])))

(defn summarize-middle [middle previous-summary call-llm-fn]
  (let [prompt (str "Summarize these conversation turns for an AI agent to continue its work.\n"
                    "Use sections: Goal, Progress, Key Decisions, Files Modified, Next Steps.\n\n"
                    (when previous-summary (str "Previous summary to update:\n" previous-summary "\n\n"))
                    "Turns to summarize:\n"
                    (str/join "\n" (map #(str "[" (:role %) "] " (subs (str (:content %)) 0 (min 500 (count (str (:content %)))))) middle)))]
    (call-llm-fn prompt)))

(defn should-compress? [messages threshold-tokens]
  (> (estimate-tokens messages) threshold-tokens))

(defn compress [messages {:keys [protect-first tail-token-budget call-llm-fn]}]
  (let [;; Aggressively prune old tool results first (keep only 2)
        pruned (prune-old-tool-results messages 2)
        [head-end tail-start] (find-boundaries pruned protect-first tail-token-budget)]
    (if (>= head-end tail-start)
      pruned ;; Nothing to compress in the middle
      (let [middle (subvec pruned head-end tail-start)
            summary (summarize-middle middle nil call-llm-fn)]
        (vec (concat (subvec pruned 0 head-end)
                     [{:role "user" :content (str "[SYSTEM: CONTEXT COMPACTION]\n"
                                                 "History summarized to save context. Original goal and latest turns preserved.\n\n"
                                                 "SUMMARY OF PREVIOUS WORK:\n" 
                                                 summary)}]
                     (subvec pruned tail-start)))))))
