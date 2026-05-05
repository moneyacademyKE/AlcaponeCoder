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
  (or (read-file-truncated (io/file (System/getProperty "user.home") ".hermes" "SOUL.md") 20000)
      "You are a helpful assistant."))

(defn load-project-context [cwd]
  (let [hermes (or (find-up cwd ".hermes.md") (find-up cwd "HERMES.md"))]
    (if hermes
      (read-file-truncated hermes 20000)
      (some #(let [f (io/file cwd %)] (when (.exists f) (read-file-truncated f 20000)))
            ["AGENTS.md" "CLAUDE.md" ".cursorrules"]))))

(defn build-system-prompt [{:keys [soul memory skills project-context]}]
  (let [parts (cond-> [soul]
                memory (conj (str "# Memory\n" memory))
                skills (conj (str "# Skills\n" skills))
                project-context (conj (str "# Project Context\n" project-context))
                :always (conj (str "Current time: " (.format (java.time.LocalDateTime/now)
                                                              (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))))]
    (str/join "\n\n" parts)))
