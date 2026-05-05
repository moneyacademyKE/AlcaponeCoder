(ns skill
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [registry]
            [cheshire.core :as json]))

(defn- get-skills-dir []
  (let [home (System/getProperty "user.home")
        dir (io/file home ".hermes" "skills")]
    (when-not (.exists dir) (.mkdirs dir))
    dir))

(defn parse-skill-md [text]
  (let [parts (str/split text #"---\n" 3)]
    (if (>= (count parts) 3)
      (let [meta-raw (nth parts 1)
            body (nth parts 2)
            meta (into {} (for [line (str/split-lines meta-raw)
                                :let [[k v] (str/split line #":" 2)]
                                :when (and k v)]
                            [(str/trim k) (str/trim v)]))]
        {:meta meta :body body})
      {:meta {} :body text})))

(defn list-skills []
  (let [dir (get-skills-dir)]
    (for [skill-dir (.listFiles dir)
          :when (.isDirectory skill-dir)
          :let [skill-md (io/file skill-dir "SKILL.md")]
          :when (.exists skill-md)]
      (let [content (slurp skill-md)
            {:keys [meta]} (parse-skill-md content)]
        {:name (.getName skill-dir)
         :description (get meta "description" "No description provided.")
         :path (.getAbsolutePath skill-dir)}))))

(defn get-skill-index-prompt []
  (let [skills (list-skills)]
    (if (empty? skills)
      nil
      (str "# Available Skills\n"
           (str/join "\n" (for [s skills] (str "- " (:name s) ": " (:description s))))))))

;; Tool Implementations
(defn skill-view-tool [{:keys [name]}]
  (let [skill-dir (io/file (get-skills-dir) name)
        skill-md (io/file skill-dir "SKILL.md")]
    (if (.exists skill-md)
      (let [{:keys [body]} (parse-skill-md (slurp skill-md))]
        body)
      (str "Skill '" name "' not found."))))

(defn skill-manage-tool [{:keys [action name description content]}]
  (let [skill-dir (io/file (get-skills-dir) name)
        skill-md (io/file skill-dir "SKILL.md")]
    (case action
      "create"
      (do
        (.mkdirs skill-dir)
        (spit skill-md (str "---\nname: " name "\ndescription: " description "\n---\n\n" content))
        (str "Skill '" name "' created."))
      
      "edit"
      (if (.exists skill-md)
        (let [{:keys [meta]} (parse-skill-md (slurp skill-md))
              new-meta (merge meta (when description {"description" description}))
              meta-str (str/join "\n" (for [[k v] new-meta] (str k ": " v)))]
          (spit skill-md (str "---\n" meta-str "\n---\n\n" content))
          (str "Skill '" name "' updated."))
        (str "Skill '" name "' not found."))
      
      "delete"
      (if (.exists skill-dir)
        (do (run! io/delete-file (reverse (file-seq skill-dir)))
            (str "Skill '" name "' deleted."))
        (str "Skill '" name "' not found."))
      
      (str "Unknown action: " action))))

;; Register the tools
(registry/register!
  {:name "skill_view"
   :handler (fn [args] (skill-view-tool (json/parse-string args true)))
   :schema {:name "skill_view"
            :description "View the full content of a skill to follow its methodology."
            :parameters {:type "object"
                         :properties {:name {:type "string" :description "The name of the skill"}}
                         :required ["name"]}}})

(registry/register!
  {:name "skill_manage"
   :handler (fn [args] (skill-manage-tool (json/parse-string args true)))
   :schema {:name "skill_manage"
            :description "Create, edit, or delete skills based on experience."
            :parameters {:type "object"
                         :properties {:action {:type "string" :enum ["create" "edit" "delete"]}
                                      :name {:type "string" :description "The name of the skill"}
                                      :description {:type "string" :description "Short description (for create/edit)"}
                                      :content {:type "string" :description "The full SKILL.md body content (for create/edit)"}}
                         :required ["action" "name"]}}})
