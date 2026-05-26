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

(defn extract-query-keywords [task]
  (if (empty? task)
    #{}
    (let [stopwords #{"the" "a" "an" "and" "or" "but" "in" "on" "at" "to" "for" "of" "with" "is" "are" "was" "were" "be" "been" "this" "that" "how" "does" "do" "into" "one" "fuses" "search" "outline" "symbol" "callers" "instead" "we" "i" "you" "he" "she" "they" "it" "my" "your" "his" "her" "their" "its" "can" "will" "would" "should" "could" "what" "why" "where" "when" "who" "which" "about" "has" "have" "had" "not" "no" "yes" "if" "then" "else"}
          words (str/split (str/lower-case task) #"\W+")]
      (->> words
           (remove stopwords)
           (filter #(> (count %) 2))
           (into #{})))))

(defn merge-intervals [intervals]
  (if (empty? intervals)
    []
    (let [sorted (sort-by first intervals)]
      (reduce (fn [acc next-interval]
                (let [last-interval (last acc)]
                  (if last-interval
                    (let [[last-start last-end] last-interval
                          [next-start next-end] next-interval]
                      (if (>= last-end next-start)
                        (conj (pop acc) [last-start (max last-end next-end)])
                        (conj acc next-interval)))
                    (conj acc next-interval))))
              [(first sorted)]
              (rest sorted)))))

(defn score-file [file keywords]
  (if-not (.exists file)
    0
    (let [name (.getName file)
          path (.getAbsolutePath file)
          content (try (slurp file) (catch Exception _ ""))
          content-lower (str/lower-case content)
          hits (reduce (fn [acc kw]
                         (+ acc (count (re-seq (re-pattern (str "\\b" kw "\\b")) content-lower))))
                       0
                       keywords)
          symbols (cond
                    (str/ends-with? name ".clj") (parse-clojure-symbols content)
                    (str/ends-with? name ".py") (parse-python-symbols content)
                    (or (str/ends-with? name ".js") (str/ends-with? name ".ts")) (parse-js-symbols content)
                    :else [])
          symbol-boost (reduce (fn [acc sym]
                                 (if (contains? keywords (str/lower-case (:name sym)))
                                   (+ acc 5)
                                   acc))
                               0
                               symbols)
          path-lower (str/lower-case path)
          penalty (cond
                    (or (str/includes? path-lower "test")
                        (str/includes? path-lower "spec")
                        (str/includes? path-lower "fixture"))
                    -3
                    
                    (or (str/ends-with? path-lower ".md")
                        (str/ends-with? path-lower ".txt")
                        (str/ends-with? path-lower ".rst"))
                    -2
                    
                    :else 0)]
      (+ hits symbol-boost penalty))))

(defn extract-context-snippets [file keywords]
  (let [lines (str/split-lines (slurp file))
        line-count (count lines)
        match-indices (keep-indexed (fn [idx line]
                                      (let [line-lower (str/lower-case line)]
                                        (when (some #(str/includes? line-lower %) keywords)
                                          (inc idx))))
                                    lines)
        windows (map (fn [idx]
                       [(max 1 (- idx 2)) (min line-count (+ idx 2))])
                     match-indices)
        merged (take 5 (merge-intervals windows))]
    (map (fn [[start end]]
           {:start start
            :end end
            :lines (subvec (vec lines) (dec start) end)})
         merged)))

(defn find-symbol-callers [root symbols defining-files]
  (let [files (list-project-files root)
        defining-set (into #{} (map #(.getAbsolutePath %) defining-files))
        callers (atom [])]
    (doseq [f files
            :when (not (contains? defining-set (.getAbsolutePath f)))]
      (let [lines (str/split-lines (try (slurp f) (catch Exception _ "")))
            name (.getName f)]
        (doseq [[idx line] (map-indexed list lines)]
          (doseq [sym symbols]
            (when (and (str/includes? line sym)
                       (not (str/includes? line (str "defn " sym)))
                       (not (str/includes? line (str "def " sym)))
                       (not (str/includes? line (str "class " sym))))
              (swap! callers conj {:symbol sym
                                   :file name
                                   :line (inc idx)
                                   :content (str/trim line)}))))))
    (take 5 @callers)))

(defn codedb-context-tool [system args]
  (let [query (:query args)
        root (or (:root-dir args) ".")
        keywords (extract-query-keywords query)]
    (if (empty? keywords)
      {:result "No relevant keywords extracted from query."}
      (let [files (list-project-files root)
            scored (->> files
                        (map (fn [f] {:file f :score (score-file f keywords)}))
                        (filter #(> (:score %) 0))
                        (sort-by :score >)
                        (take 3))
            top-files (map :file scored)
            root-path (.getAbsolutePath (io/file root))
            root-len (count root-path)
            symbols-map (into {} (for [f top-files
                                       :let [content (try (slurp f) (catch Exception _ ""))
                                             name (.getName f)
                                             syms (cond
                                                    (str/ends-with? name ".clj") (parse-clojure-symbols content)
                                                    (str/ends-with? name ".py") (parse-python-symbols content)
                                                    (or (str/ends-with? name ".js") (str/ends-with? name ".ts")) (parse-js-symbols content)
                                                    :else [])]]
                                   [f syms]))
            all-top-symbols (into #{} (map :name (apply concat (vals symbols-map))))
            callers (find-symbol-callers root all-top-symbols top-files)
            snippets (for [{:keys [file score]} scored
                           :let [p (.getAbsolutePath file)
                                 rel (subs p root-len)
                                 clean-rel (if (or (str/starts-with? rel "/")
                                                   (str/starts-with? rel "\\"))
                                             (subs rel 1)
                                             rel)
                                 snips (extract-context-snippets file keywords)]]
                       {:path clean-rel
                        :score score
                        :snippets snips})
            md (with-out-str
                 (println "# Fused CodeDB Context")
                 (println)
                 (println "## Extracted Keywords")
                 (println (str/join ", " (sort keywords)))
                 (println)
                 (println "## Ranked Relevant Files & Snippets")
                 (doseq [s snippets]
                   (println (str "### File: `" (:path s) "` (Score: " (:score s) ")"))
                   (if (empty? (:snippets s))
                     (println "*(No matching code snippets found)*")
                     (doseq [snip (:snippets s)]
                       (println "```clojure")
                       (doseq [[idx line] (map-indexed list (:lines snip))]
                         (let [line-num (+ (:start snip) idx)]
                           (println (str line-num ": " line))))
                       (println "```")))
                   (println))
                 (println "## Reference Index / Caller Chains")
                 (if (empty? callers)
                   (println "*(No caller chains identified)*")
                   (doseq [c callers]
                     (println (str "- Usage of `" (:symbol c) "` in `" (:file c) "` at line " (:line c) ": `" (:content c) "`")))))]
        {:result md}))))

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
                                                 :required []}}})
      (registry/register {:name "codedb_context"
                          :handler codedb-context-tool
                          :schema {:type "function"
                                   :description "Fuses search, outline, symbol, and callers into a single ranked context response."
                                   :parameters {:type "object"
                                                 :properties {:query {:type "string" :description "The natural-language task query."}
                                                              :root-dir {:type "string" :description "Root directory path."}}
                                                 :required ["query"]}}})))
