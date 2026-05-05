(ns eval
  (:require [clojure.string :as str]))

(defrecord FitnessScore [correctness procedure-following conciseness feedback])

(defn validate-constraints [skill-text]
  (let [size (count skill-text)]
    {:size size
     :valid? (and (pos? size)
                  (< size 15000)
                  (or (str/starts-with? skill-text "#")
                      (str/starts-with? skill-text "---")))
     :errors (cond-> []
               (not (pos? size)) (conj "Empty skill text")
               (>= size 15000) (conj "Skill text too large")
               (not (or (str/starts-with? skill-text "#")
                        (str/starts-with? skill-text "---"))) (conj "Invalid skill structure"))}))

(defn llm-judge [skill task output expected]
  ;; Mocking the LLM-as-judge scoring
  (let [score (if (str/includes? (str/lower-case output) (str/lower-case expected)) 1.0 0.5)]
    (->FitnessScore score 0.8 0.9 "Looks good but could be more specific.")))
