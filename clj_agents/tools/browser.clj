(ns tools.browser
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.process :refer [shell]]
            [registry]))

(defn- get-or-start-driver! [system]
  (if-let [browser-atom (:browser-process system)]
    (if-let [p @browser-atom]
      p
      (try
        (let [p (babashka.process/process ["node" "scripts/browser_driver_daemon.js"] 
                                           {:in :pipe :out :pipe :err :inherit})]
          (reset! browser-atom p)
          p)
        (catch Exception e
          (throw (ex-info "Failed to start browser driver daemon. Is node installed?" 
                          {:original-error e})))))
    (throw (AssertionError. "System missing :browser-process atom"))))

(defn- run-driver [system action & args]
  (let [p (get-or-start-driver! system)
        payload (apply assoc {:action action} args)
        in (:in p)
        out (:out p)]
    (locking p
      (.write (clojure.java.io/writer in) (str (json/generate-string payload) "\n"))
      (.flush (clojure.java.io/writer in))
      (let [line (.readLine (clojure.java.io/reader out))]
        (if line
          (let [data (json/parse-string line true)]
            (if (= "ok" (:status data))
              (:result data)
              (str "Driver error: " (:message data))))
          "Driver error: No response from daemon")))))

(defn- extract-refs [snapshot]
  (let [lines (str/split-lines snapshot)
        ref-pattern #"\[ref=(e\d+)\]"]
    (into {} (for [line lines
                   :let [m (re-find ref-pattern line)]
                   :when m]
               [(second m) line]))))

;; Browser state is kept in the system's :browser-process atom (the driver process).
;; Per-session URL/snapshot state is tracked in the mutable driver daemon itself.

(defn navigate-handler [system arguments]
  (let [args (json/parse-string arguments true)
        url (:url args)
        result (run-driver system "navigate" :url url)]
    (json/generate-string {:result result})))

(defn snapshot-handler [system _]
  (let [result (run-driver system "snapshot")]
    (json/generate-string {:result result})))

(defn click-handler [system arguments]
  (let [args (json/parse-string arguments true)
        ref (:ref args)
        result (run-driver system "click" :ref ref)]
    (json/generate-string {:result result})))

(defn type-handler [system arguments]
  (let [args (json/parse-string arguments true)
        ref (:ref args)
        text (:text args)
        result (run-driver system "type" :ref ref :text text)]
    (json/generate-string {:result result})))

(defn register-tools [system]
  (-> system
      (registry/register
       {:name "browser_navigate"
        :handler navigate-handler
        :schema {:type "function"
                 :function {:name "browser_navigate"
                            :description "Open a URL and get a snapshot."
                            :parameters {:type "object"
                                         :properties {:url {:type "string"}}
                                         :required ["url"]}}}})
      (registry/register
       {:name "browser_snapshot"
        :handler snapshot-handler
        :schema {:type "function"
                 :function {:name "browser_snapshot"
                            :description "Get the current page snapshot."
                            :parameters {:type "object" :properties {}}}}})
      (registry/register
       {:name "browser_click"
        :handler click-handler
        :schema {:type "function"
                 :function {:name "browser_click"
                            :description "Click an element by reference."
                            :parameters {:type "object"
                                         :properties {:ref {:type "string"}}
                                         :required ["ref"]}}}})
      (registry/register
       {:name "browser_type"
        :handler type-handler
        :schema {:type "function"
                 :function {:name "browser_type"
                            :description "Type text into an element."
                            :parameters {:type "object"
                                         :properties {:ref {:type "string"}
                                                      :text {:type "string"}}
                                         :required ["ref" "text"]}}}})))

(defn register-tools! [system] (register-tools system)) ;; legacy alias
