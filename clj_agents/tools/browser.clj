(ns tools.browser
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [registry]))

(defonce browser-state (atom {:url nil :snapshot nil :cookies {}}))

(defn- get-mock-snapshot [url]
  (cond
    (str/includes? url "github.com")
    "navigation \"GitHub\"\n  link \"Sign in\" [ref=e1]\n  link \"Sign up\" [ref=e2]\n  search \"Search GitHub\" [ref=e3]\n  heading \"Let's build from here\" [level=1]"
    
    (str/includes? url "google.com")
    "navigation \"Google\"\n  search \"Search\" [ref=e1]\n  button \"Google Search\" [ref=e2]\n  button \"I'm Feeling Lucky\" [ref=e3]"
    
    :else
    (str "navigation \"" url "\"\n  text \"Page loaded successfully.\"")))

(defn navigate-handler [arguments]
  (let [args (json/parse-string arguments true)
        url (:url args)]
    (swap! browser-state assoc :url url :snapshot (get-mock-snapshot url))
    (json/generate-string {:result (str "Navigated to " url "\n\n" (:snapshot @browser-state))})))

(defn snapshot-handler [_]
  (json/generate-string {:result (:snapshot @browser-state)}))

(defn click-handler [arguments]
  (let [args (json/parse-string arguments true)
        ref (:ref args)]
    (json/generate-string {:result (str "Clicked element " ref)})))

(defn type-handler [arguments]
  (let [args (json/parse-string arguments true)
        ref (:ref args)
        text (:text args)]
    (json/generate-string {:result (str "Typed '" text "' into " ref)})))

(registry/register!
 {:name "browser_navigate"
  :handler navigate-handler
  :schema {:type "function"
           :function {:name "browser_navigate"
                      :description "Open a URL and get a snapshot."
                      :parameters {:type "object"
                                   :properties {:url {:type "string"}}
                                   :required ["url"]}}}})

(registry/register!
 {:name "browser_snapshot"
  :handler snapshot-handler
  :schema {:type "function"
           :function {:name "browser_snapshot"
                      :description "Get the current page snapshot."
                      :parameters {:type "object" :properties {}}}}})

(registry/register!
 {:name "browser_click"
  :handler click-handler
  :schema {:type "function"
           :function {:name "browser_click"
                      :description "Click an element by reference."
                      :parameters {:type "object"
                                   :properties {:ref {:type "string"}}
                                   :required ["ref"]}}}})

(registry/register!
 {:name "browser_type"
  :handler type-handler
  :schema {:type "function"
           :function {:name "browser_type"
                      :description "Type text into an element."
                      :parameters {:type "object"
                                   :properties {:ref {:type "string"}
                                                :text {:type "string"}}
                                   :required ["ref" "text"]}}}})
