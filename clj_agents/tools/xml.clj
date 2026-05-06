(ns tools.xml
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [registry]
            [cheshire.core :as json]
            [logger]))

(defn- find-and-update [node tag-name new-value]
  (if (and (map? node) (:tag node)
           (= (name (:tag node)) tag-name))
    (assoc node :content [new-value])
    (if (and (map? node) (:tag node))
      (update node :content (fn [content]
                              (mapv #(find-and-update % tag-name new-value) content)))
      node)))

(defn xml-tool [system {:keys [action path tag value]}]
  (try
    (let [f (io/file path)]
      (if (and (not= action "create") (not (.exists f)))
        (str "Error: File not found: " path)
        (case action
        "read"
        (let [root (xml/parse-str (slurp f))]
          (xml/indent-str root))

        "update-tag"
        (let [root (xml/parse-str (slurp f))
              updated (find-and-update root tag value)]
          (spit f (xml/indent-str updated))
          (str "Successfully updated tag <" tag "> in " path))

          "create"
          (let [root (xml/element (keyword tag) {} value)]
            (spit f (xml/indent-str root))
            (str "Created XML file " path " with root <" tag ">"))

          (str "Error: Unknown action " action))))
    (catch Exception e
      (str "Error processing XML: " (ex-message e)))))

(defn register-tools [system]
  (registry/register
   system
   {:name "xml_tool"
    :handler (fn [s args] (xml-tool s (json/parse-string args true)))
    :schema {:type "function"
             :function {:name "xml_tool"
                        :description "Safe, data-driven XML manipulation for poms, manifests, and configs."
                        :parameters {:type "object"
                                     :properties {:action {:type "string" :enum ["read" "update-tag" "create"]}
                                                  :path {:type "string" :description "Path to the XML file"}
                                                  :tag {:type "string" :description "Tag name to target"}
                                                  :value {:type "string" :description "New content for the tag"}}
                                     :required ["action" "path"]}}}}))
