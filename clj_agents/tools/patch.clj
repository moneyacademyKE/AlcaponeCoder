(ns tools.patch
  (:require [registry]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(defn patch-tool [{:keys [path find replace]}]
  (let [file (io/file path)]
    (if-not (.exists file)
      (str "Error: File not found: " path)
      (let [content (slurp file)]
        (if (not (str/includes? content find))
          (str "Error: String to find not found in file: " find)
          (let [new-content (str/replace-first content find replace)]
            (spit file new-content)
            (str "Successfully patched " path)))))))

(defn register-tools [system]
  (registry/register
   system
   {:name "patch"
    :handler (fn [system args] (patch-tool (json/parse-string args true)))
    :schema {:type "function"
             :function {:name "patch"
                        :description "Replace the first occurrence of a string in a file with another string. Useful for precise code fixes."
                        :parameters {:type "object"
                                     :properties {:path {:type "string" :description "Path to the file"}
                                                  :find {:type "string" :description "The exact string to find"}
                                                  :replace {:type "string" :description "The replacement string"}}
                                     :required ["path" "find" "replace"]}}}}))

(defn register-tools! [system] (register-tools system)) ;; legacy alias
