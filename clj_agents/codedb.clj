(ns codedb
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [registry]))

(def ignore-dirs #{".git" "node_modules" "target" ".shadow-cljs"})

(defn list-project-files [dir]
  (let [dir-file (io/file dir)
        dir-path (.getAbsolutePath dir-file)
        dir-len (count dir-path)]
    (if (.exists dir-file)
      (filter (fn [f]
                (let [path (.getAbsolutePath f)
                      rel-path (subs path dir-len)
                      parts (str/split rel-path #"[\/\\]")]
                  (and (.isFile f)
                       (not (some ignore-dirs parts)))))
              (file-seq dir-file))
      [])))

(defn parse-clojure-symbols [content]
  (let [lines (str/split-lines content)]
    (->> (map-indexed 
           (fn [idx line]
             (concat
               (for [[_ name] (re-seq #"\(\s*defn\b\s+([^\s\[\(\)]+)" line)]
                 {:type "function" :name name :line (inc idx)})
               (for [[_ name] (re-seq #"\(\s*def\b\s+([^\s\(\)]+)" line)
                     :when (not= name "n")] ;; exclude 'n' to avoid matching defn
                 {:type "variable" :name name :line (inc idx)})))
           lines)
         (apply concat)
         (into []))))

(defn parse-python-symbols [content]
  (let [lines (str/split-lines content)]
    (->> (map-indexed 
           (fn [idx line]
             (cond
               (re-find #"^\s*class\s+" line)
               [{:type "class" :name (second (re-find #"class\s+([a-zA-Z0-9_]+)" line)) :line (inc idx)}]
               
               (re-find #"^def\s+" line)
               [{:type "function" :name (second (re-find #"def\s+([a-zA-Z0-9_]+)" line)) :line (inc idx)}]
               
               (re-find #"^\s+def\s+" line)
               [{:type "method" :name (second (re-find #"def\s+([a-zA-Z0-9_]+)" line)) :line (inc idx)}]
               
               :else nil))
           lines)
         (apply concat)
         (into []))))

(defn parse-js-symbols [content]
  (let [lines (str/split-lines content)]
    (->> (map-indexed 
           (fn [idx line]
             (concat
               (for [[_ name] (re-seq #"\bfunction\s+([a-zA-Z0-9_]+)" line)]
                 {:type "function" :name name :line (inc idx)})
               (for [[_ name] (re-seq #"\b(?:const|let|var)\s+([a-zA-Z0-9_]+)" line)]
                 {:type "variable" :name name :line (inc idx)})
               (for [[_ name] (re-seq #"\bclass\s+([a-zA-Z0-9_]+)" line)]
                 {:type "class" :name name :line (inc idx)})))
           lines)
         (apply concat)
         (into []))))

(defn search-files-grep [dir query]
  (let [query-lower (str/lower-case query)
        files (filter #(.isFile %) (list-project-files dir))
        matches (atom [])]
    (doseq [f files]
      (try
        (let [lines (str/split-lines (slurp f))]
          (doseq [[idx line] (map-indexed list lines)]
            (when (str/includes? (str/lower-case line) query-lower)
              (swap! matches conj {:file (.getPath f) :line (inc idx) :content (str/trim line)}))))
        (catch Exception _ nil)))
    (into [] (take 50 @matches))))

(defn extract-clojure-deps [content]
  (let [normalized (str/replace content #"\n" " ")
        ns-decl (second (re-find #"\(\s*ns\b[^\(\)]*(\(\s*\:require\b[^\)]+\))" normalized))]
    (if ns-decl
      (->> (re-seq #"\[\s*([a-zA-Z0-9\.\-\_]+)" ns-decl)
           (map second)
           (into []))
      [])))

(defn codedb-tree-tool [system args]
  (let [root (or (:root-dir args) ".")
        root-file (io/file root)
        files (list-project-files root-file)
        root-path (.getAbsolutePath root-file)
        root-len (count root-path)
        tree-data (keep (fn [f]
                          (let [p (.getAbsolutePath f)
                                rel (subs p root-len)
                                clean-rel (if (or (str/starts-with? rel "/")
                                                  (str/starts-with? rel "\\"))
                                            (subs rel 1)
                                            rel)]
                            (when (seq clean-rel)
                              {:path clean-rel
                               :size (.length f)})))
                        files)]
    {:result (json/generate-string tree-data)}))

(defn codedb-hot-tool [system args]
  (let [root (or (:root-dir args) ".")
        root-file (io/file root)
        root-path (.getAbsolutePath root-file)
        root-len (count root-path)
        git-files (try
                    (let [res (shell/sh "git" "status" "--porcelain" :dir root-path)]
                      (if (= 0 (:exit res))
                        (keep (fn [line]
                                (let [trimmed (str/trim line)
                                      parts (str/split trimmed #"\s+")]
                                  (when (>= (count parts) 2)
                                    {:status (first parts)
                                     :path (str/join " " (rest parts))})))
                              (str/split-lines (:out res)))
                        []))
                    (catch Exception _ []))
        recent-files (->> (list-project-files root-file)
                          (sort-by (fn [f] (.lastModified f)) >)
                          (take 10)
                          (keep (fn [f]
                                  (let [p (.getAbsolutePath f)
                                        rel (subs p root-len)
                                        clean-rel (if (or (str/starts-with? rel "/")
                                                         (str/starts-with? rel "\\"))
                                                    (subs rel 1)
                                                    rel)]
                                    (when (seq clean-rel)
                                      {:path clean-rel
                                       :last-modified (.lastModified f)
                                       :size (.length f)})))))]
    {:result (json/generate-string {:git-status (or git-files [])
                                    :recently-modified recent-files})}))

(defn codedb-outline-tool [system args]
  (let [file-path (:path args)
        f (io/file file-path)]
    (if (.exists f)
      (let [content (slurp f)
            symbols (cond
                      (str/ends-with? file-path ".clj") (parse-clojure-symbols content)
                      (str/ends-with? file-path ".py") (parse-python-symbols content)
                      (or (str/ends-with? file-path ".js") (str/ends-with? file-path ".ts")) (parse-js-symbols content)
                      :else [])]
        {:result (json/generate-string symbols)})
      {:result (str "Error: File not found: " file-path)})))

(defn codedb-search-tool [system args]
  (let [query (:query args)
        root (or (:root-dir args) ".")
        matches (search-files-grep root query)]
    {:result (json/generate-string matches)}))

(defn codedb-deps-tool [system args]
  (let [root (or (:root-dir args) ".")
        files (filter #(.isFile %) (list-project-files root))
        dep-map (into {} (for [f files
                               :let [name (.getName f)
                                     content (try (slurp f) (catch Exception _ ""))
                                     deps (if (str/ends-with? name ".clj")
                                            (extract-clojure-deps content)
                                            [])]]
                           [name deps]))]
    {:result (json/generate-string dep-map)}))

(defn generate-codebase-map [root]
  (let [root-file (io/file root)
        root-path (.getAbsolutePath root-file)
        root-len (count root-path)
        files (list-project-files root-file)
        relative-paths (keep (fn [f]
                               (let [p (.getAbsolutePath f)
                                     rel (subs p root-len)
                                     clean-rel (if (or (str/starts-with? rel "/")
                                                      (str/starts-with? rel "\\"))
                                                 (subs rel 1)
                                                 rel)]
                                 (when (seq clean-rel)
                                   clean-rel)))
                             files)]
    (str "# Codebase Map (Files & Structure)\n"
         (str/join "\n" (map #(str "- " %) (sort relative-paths))))))

(defn register-tools [system]
  (-> system
      (registry/register {:name "codedb_tree"
                          :handler codedb-tree-tool
                          :schema {:type "function"
                                   :description "Compact workspace directory structure with file sizes."
                                   :parameters {:type "object"
                                                 :properties {:root-dir {:type "string" :description "Root directory path."}}
                                                 :required []}}})
      (registry/register {:name "codedb_hot"
                          :handler codedb-hot-tool
                          :schema {:type "function"
                                   :description "Git status and recently modified files list."
                                   :parameters {:type "object"
                                                 :properties {:root-dir {:type "string" :description "Root directory path."}}
                                                 :required []}}})
      (registry/register {:name "codedb_outline"
                          :handler codedb-outline-tool
                          :schema {:type "function"
                                   :description "Outline file functions, classes, and variables."
                                   :parameters {:type "object"
                                                 :properties {:path {:type "string" :description "File path."}}
                                                 :required ["path"]}}})
      (registry/register {:name "codedb_search"
                          :handler codedb-search-tool
                          :schema {:type "function"
                                   :description "Workspace text search."
                                   :parameters {:type "object"
                                                 :properties {:query {:type "string" :description "Search query."}
                                                              :root-dir {:type "string" :description "Root directory path."}}
                                                 :required ["query"]}}})
      (registry/register {:name "codedb_deps"
                          :handler codedb-deps-tool
                          :schema {:type "function"
                                   :description "Workspace dependency relations."
                                   :parameters {:type "object"
                                                 :properties {:root-dir {:type "string" :description "Root directory path."}}
                                                 :required []}}})))
