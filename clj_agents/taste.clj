(ns taste
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [llm]))

(defn get-hermes-home []
  (let [env-home (System/getenv "HERMES_HOME")
        default-home (str (System/getProperty "user.home") "/.hermes")]
    (io/file (or env-home default-home))))

(defn load-taste-profile []
  (let [home (get-hermes-home)
        f (io/file home "taste.json")]
    (if (.exists f)
      (try (json/parse-string (slurp f) true)
           (catch Exception _ {:idioms [] :anti-patterns [] :success-score 1.0}))
      {:idioms ["Use clojure.spec.alpha for validating data structures at system boundaries"
                "Prefer pure data transformations using mini-specter"
                "Ensure functions are de-complected and perform single, cohesive operations"]
       :anti-patterns ["Do not use global mutable atoms for tool registries"
                       "Avoid long, nested update-in chains where mini-specter paths would be cleaner"]
       :success-score 1.0})))

(defn save-taste-profile! [profile]
  (let [home (get-hermes-home)
        _ (.mkdirs home)
        f (io/file home "taste.json")]
    (spit f (json/generate-string profile))))

(defn evaluate-symbolic-reward [command-output]
  (let [exit-code (:exit command-output)
        out-str (str (:out command-output))]
    (if (and (= 0 exit-code)
             (not (str/includes? out-str "FAIL")))
      1.0
      0.0)))

(defn evaluate-neural-reward [system trajectory-turn code-delta]
  (let [judge-prompt (str "You are a Senior Clojure Quality and Style Judge. Rate the following code edit on a scale of 0.0 to 1.0
                           based on idiomatic Clojure structure (purity, simplicity, de-complected logic, and spec usage).
                           
                           CODE EDIT:
                           " code-delta "
                           
                           Respond with ONLY a decimal number between 0.0 and 1.0 (e.g., 0.85).")
        rating-str (llm/call-auxiliary-llm system judge-prompt)]
    (try (Double/parseDouble (str/trim rating-str))
         (catch Exception _ 0.5))))

(defn calculate-reward [system env command-output code-delta]
  (let [sym-r (evaluate-symbolic-reward command-output)
        neu-r (evaluate-neural-reward system nil code-delta)]
    (+ (* 0.6 sym-r) (* 0.4 neu-r))))

(defn update-taste-from-trajectory! [system messages success?]
  (println "[BG-REVIEW] Updating taste profile from conversation trajectory...")
  (let [profile (load-taste-profile)
        history-text (str/join "\n" (map #(str (:role %) ": " (:content %)) messages))
        prompt (str "Analyze the following agent trajectory. Extract 1-2 coding style preferences (idioms)
                     or anti-patterns specific to this codebase that led to " (if success? "SUCCESS" "FAILURE") ".
                     
                     TRAJECTORY:
                     " history-text "
                     
                     Output your findings as a raw JSON object of the form:
                     {
                       \"new_idioms\": [\"idiom description...\"],
                       \"new_anti_patterns\": [\"anti-pattern description...\"]
                     }
                     
                     If no new idioms or anti-patterns are found, output empty lists.")
        response-str (llm/call-auxiliary-llm system prompt)
        reflections (try (json/parse-string response-str true)
                         (catch Exception _ {:new_idioms [] :new_anti_patterns []}))]
    (let [updated-profile (-> profile
                              (update :idioms #(into [] (distinct (concat % (:new_idioms reflections)))))
                              (update :anti-patterns #(into [] (distinct (concat % (:new_anti_patterns reflections)))))
                              (assoc :success-score (if success? 
                                                      (min 1.0 (+ (:success-score profile) 0.05))
                                                      (max 0.0 (- (:success-score profile) 0.1)))))]
      (save-taste-profile! updated-profile)
      (println "[BG-REVIEW] Taste profile updated successfully."))))

(defn format-taste-prompt [profile]
  (str "\n## Coding Taste & Idiomatic Constraints\n"
       "Enforce the following architectural style preferences:\n"
       (str/join "\n" (map #(str "- [DO] " %) (:idioms profile)))
       "\n\nAvoid the following anti-patterns:\n"
       (str/join "\n" (map #(str "- [DON'T] " %) (:anti-patterns profile)))))
