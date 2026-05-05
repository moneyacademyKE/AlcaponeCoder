(ns mcp
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :refer [process]]
            [registry]))

(defn- send-rpc! [out method params id]
  (let [msg (json/generate-string {:jsonrpc "2.0" :method method :params params :id id})]
    (.write out (str msg "\n"))
    (.flush out)))

(defn- read-rpc! [in]
  (let [line (.readLine in)]
    (json/parse-string line true)))

(defrecord MCPServer [name process in out])

(defn connect-stdio [server-name command args env]
  (let [p (process (into [command] args) {:in :pipe :out :pipe :err :inherit :env env})
        out (io/writer (:in p))
        in (io/reader (:out p))]
    ;; 1. Initialize
    (send-rpc! out "initialize" {:protocolVersion "2024-11-05" :capabilities {} :clientInfo {:name "hermes-clj"}} 1)
    (read-rpc! in)
    (->MCPServer server-name p in out)))

(defn list-tools [server]
  (send-rpc! (:out server) "tools/list" {} 2)
  (:tools (:result (read-rpc! (:in server)))))

(defn call-tool [server tool-name args]
  (send-rpc! (:out server) "tools/call" {:name tool-name :arguments args} 3)
  (let [res (read-rpc! (:in server))]
    (if (:error res)
      (throw (Exception. (str "MCP Error: " (:error res))))
      (str/join "\n" (map :text (get-in res [:result :content]))))))

(defn register-mcp-tools! [server]
  (let [tools (list-tools server)]
    (doseq [t tools]
      (let [prefixed-name (str "mcp_" (:name server) "_" (:name t))]
        (registry/register!
         {:name prefixed-name
          :handler (fn [args]
                     (let [parsed-args (json/parse-string args true)]
                       (call-tool server (:name t) parsed-args)))
          :schema {:type "function"
                   :function {:name prefixed-name
                              :description (:description t)
                              :parameters (:inputSchema t)}})))))
