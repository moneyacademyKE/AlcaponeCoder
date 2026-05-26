(ns codedb
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [registry]))

(def ignore-dirs #{".git" "node_modules" "target" ".shadow-cljs"})

(defn list-project-files [dir]
  (let [dir-file (io/file dir)
        dir-path (.getAbsolutePath dir-file)]
    (if (.exists dir-file)
      (filter (fn [f]
                (let [path (.getAbsolutePath f)
                      rel-path (str/replace path dir-path "")
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
        files (list-project-files root)
        root-path (.getAbsolutePath (io/file root))
        relative-paths (map (fn [f]
                              (let [p (.getAbsolutePath f)]
                                (str/replace p (str root-path "/") "")))
                            files)]
    {:result (json/generate-string (filter seq relative-paths))}))

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

(defn register-tools [system]
  (-> system
      (registry/register {:name "codedb_tree"
                          :handler codedb-tree-tool
                          :schema {:type "function"
                                   :description "Compact workspace directory structure."
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
