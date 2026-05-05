(ns prompt
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn read-file-truncated [path max-chars]
  (let [file (io/file path)]
    (if (.exists file)
      (let [content (slurp file)]
        (if (> (count content) max-chars)
          (str (subs content 0 max-chars) "\n\n[...truncated...]")
          content))
      nil)))

(defn find-up [start-dir filename]
  (loop [curr (io/file start-dir)]
    (let [f (io/file curr filename)]
      (if (.exists f)
        (.getAbsolutePath f)
        (let [parent (.getParentFile curr)]
          (if (nil? parent)
            nil
            (recur parent)))))))

(defn load-soul []
  (or (some-> (io/resource "SOUL.md") slurp)
      (read-file-truncated (io/file "SOUL.md") 20000)
      (read-file-truncated (io/file (System/getProperty "user.home") ".hermes" "SOUL.md") 20000)
      "You are Hermes, an elite AI orchestrator. 

CRITICAL METHODOLOGY:
1. Rich Hickey Gap Analysis: Always evaluate simplicity vs. complexity before taking action.
2. Red/Green TDD: Write failing tests (or verifiers) first, then write the minimal code to pass them.
3. Systematized Debugging: Do not guess. Write isolated test cases. Use `strace`, `gdb`, or print debugging to prove hypotheses.
4. Self-Scoring & Verification: Always attempt to run the provided verifier scripts (e.g., `./tests/test.sh` or similar) before finishing. This provides empirical proof of success.
5. Completeness: Ensure your solution correctly handles edge cases and passes all validation before concluding.

You are operating within a restricted sandbox. Use your tools carefully. Always verify the results of your actions."))

(defn load-project-context [cwd]
  (let [hermes (or (find-up cwd ".hermes.md") (find-up cwd "HERMES.md"))]
    (if hermes
      (read-file-truncated hermes 20000)
      (some #(let [f (io/file cwd %)] (when (.exists f) (read-file-truncated f 20000)))
            ["AGENTS.md" "CLAUDE.md" ".cursorrules"]))))

(defn build-system-prompt [{:keys [soul memory skills project-context]}]
  (let [knowledge (or (some-> (io/resource "KNOWLEDGE.md") slurp)
                      (some-> (io/file "KNOWLEDGE.md") slurp)
                      "No institutional knowledge available.")
        parts (cond-> [soul]
                :always (conj (str "# Institutional Knowledge (Operational Patterns)\n" knowledge))
                :always (conj "ENVIRONMENT CAPABILITIES:\nCommon binaries available: git, python3, gcc, g++, gdb, strace, valgrind, sqlite3, curl, wget, nginx, sshd, pdftotext, tesseract.")
                memory (conj (str "# Memory\n" memory))
                skills (conj (str "# Skills\n" skills))
                project-context (conj (str "# Project Context\n" project-context))
                :always (conj (str "Current time: " (.format (java.time.LocalDateTime/now)
                                                              (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))))]
    (str/join "\n\n" parts)))
