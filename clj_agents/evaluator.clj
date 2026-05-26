(ns evaluator
  (:require [clojure.string :as str]
            [llm]))

(defn beats?
  "Checks if run-a strictly dominates run-b across three axes.
   - Quality (higher is better)
   - Speed (lower is better, duration in ms)
   - Tokens (lower is better)"
  [run-a run-b]
  (and (>= (:quality run-a) (:quality run-b))
       (<= (:speed run-a) (:speed run-b))
       (<= (:tokens run-a) (:tokens run-b))
       (or (> (:quality run-a) (:quality run-b))
           (< (:speed run-a) (:speed run-b))
           (< (:tokens run-a) (:tokens run-b)))))

(defn pareto-optimal?
  "Checks if a candidate run is Pareto-optimal within a comparison set of runs."
  [candidate all-runs]
  (not (some #(beats? % candidate) all-runs)))

(defn parse-rubric-response
  "Parses scores and feedback from LLM-as-judge response output text."
  [response]
  (let [response-str (or response "")
        file-correct (some-> (re-find #"(?i)file\s+correct:\s*(\d+)" response-str) second Integer/parseInt)
        function-correct (some-> (re-find #"(?i)function\s+correct:\s*(\d+)" response-str) second Integer/parseInt)
        snippet-faithful (some-> (re-find #"(?i)snippet\s+faithful:\s*(\d+)" response-str) second Integer/parseInt)
        explanation-accurate (some-> (re-find #"(?i)explanation\s+accurate:\s*(\d+)" response-str) second Integer/parseInt)
        completeness (some-> (re-find #"(?i)completeness:\s*(\d+)" response-str) second Integer/parseInt)
        feedback (some-> (re-find #"(?i)feedback:\s*([\s\S]+)" response-str) second str/trim)]
    {:file-correct (or file-correct 0)
     :function-correct (or function-correct 0)
     :snippet-faithful (or snippet-faithful 0)
     :explanation-accurate (or explanation-accurate 0)
     :completeness (or completeness 0)
     :feedback (or feedback "")}))

(defn score-rubric
  "Invokes the auxiliary LLM to grade a run based on the 5-point rubric."
  [system task-desc response-text execution-trace]
  (let [prompt (str "You are a software engineering evaluator. Grade the agent's task execution on a 5-point rubric (0 to 5, where 0 is completely incorrect and 5 is perfectly correct/complete).\n\n"
                    "TASK DESCRIPTION:\n" task-desc "\n\n"
                    "AGENT RESPONSE:\n" response-text "\n\n"
                    "EXECUTION TRACE (Tool Calls & Outputs):\n" execution-trace "\n\n"
                    "Grade the run on these 5 dimensions:\n"
                    "1. FILE CORRECT: Did the agent inspect/edit the correct files?\n"
                    "2. FUNCTION CORRECT: Did the agent target/analyze the correct functions/symbols?\n"
                    "3. SNIPPET FAITHFUL: Are the inlined/referenced code snippets accurate?\n"
                    "4. EXPLANATION ACCURATE: Is the reasoning/explanation correct?\n"
                    "5. COMPLETENESS: Did the agent resolve all task requirements?\n\n"
                    "Provide your response in this exact format:\n"
                    "FILE CORRECT: <score>\n"
                    "FUNCTION CORRECT: <score>\n"
                    "SNIPPET FAITHFUL: <score>\n"
                    "EXPLANATION ACCURATE: <score>\n"
                    "COMPLETENESS: <score>\n"
                    "FEEDBACK: <detailed commentary>")
        response (llm/call-auxiliary-llm system prompt)]
    (parse-rubric-response response)))
