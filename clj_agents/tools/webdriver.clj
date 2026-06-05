(ns tools.webdriver
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn request [method url payload]
  (let [opts (cond-> {:headers {"Content-Type" "application/json"}
                      :throw false
                      :timeout 30000}
               payload (assoc :body (json/generate-string payload)))]
    (case method
      :get (http/get url opts)
      :post (http/post url opts)
      :delete (http/delete url opts))))

(defn create-session [base-url capabilities]
  (let [res (request :post (str base-url "/session") {:capabilities capabilities})
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      (get-in body [:value :sessionId])
      (throw (ex-info (str "Failed to create session: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))

(defn close-session [base-url session-id]
  (let [res (request :delete (str base-url "/session/" session-id) nil)]
    (= 200 (:status res))))

(defn navigate [base-url session-id url]
  (let [res (request :post (str base-url "/session/" session-id "/url") {:url url})
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      true
      (throw (ex-info (str "Failed to navigate: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))

(defn get-current-url [base-url session-id]
  (let [res (request :get (str base-url "/session/" session-id "/url") nil)
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      (get-in body [:value])
      (throw (ex-info (str "Failed to get URL: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))

(defn get-title [base-url session-id]
  (let [res (request :get (str base-url "/session/" session-id "/title") nil)
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      (get-in body [:value])
      (throw (ex-info (str "Failed to get title: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))

(defn find-element [base-url session-id using value]
  (let [res (request :post (str base-url "/session/" session-id "/element") {:using using :value value})
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      (let [elem-val (get body :value)
            elem-id (if (map? elem-val)
                      (some (fn [[k v]] (when (str/starts-with? (name k) "element-") v))
                            elem-val)
                      elem-val)]
        (or elem-id (get-in body [:value :ELEMENT])))
      (throw (ex-info (str "Failed to find element: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))

(defn click-element [base-url session-id element-id]
  (let [res (request :post (str base-url "/session/" session-id "/element/" element-id "/click") {})
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      true
      (throw (ex-info (str "Failed to click element: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))

(defn send-keys [base-url session-id element-id text]
  (let [res (request :post (str base-url "/session/" session-id "/element/" element-id "/value") {:text text})
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      true
      (throw (ex-info (str "Failed to send keys: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))

(defn get-page-source [base-url session-id]
  (let [res (request :get (str base-url "/session/" session-id "/source") nil)
        body (json/parse-string (:body res) true)]
    (if (= 200 (:status res))
      (get-in body [:value])
      (throw (ex-info (str "Failed to get source: " (get-in body [:value :message]))
                      {:status (:status res) :body body})))))
