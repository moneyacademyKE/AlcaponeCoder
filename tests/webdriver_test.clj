(ns webdriver-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [tools.webdriver :as wd]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

(deftest test-webdriver-functional-pipeline
  (testing "Stateless W3C WebDriver HTTP Commands"
    (let [base-url "http://localhost:9515"
          requests (atom [])
          mock-http-fn (fn [method url opts]
                         (swap! requests conj {:method method :url url :opts opts})
                         (cond
                           (and (= method :post) (= url "http://localhost:9515/session"))
                           {:status 200 :body "{\"value\": {\"sessionId\": \"session-999\"}}"}

                           (and (= method :post) (= url "http://localhost:9515/session/session-999/url"))
                           {:status 200 :body "{\"value\": null}"}

                           (and (= method :get) (= url "http://localhost:9515/session/session-999/title"))
                           {:status 200 :body "{\"value\": \"Test Page Title\"}"}

                           (and (= method :post) (= url "http://localhost:9515/session/session-999/element"))
                           {:status 200 :body "{\"value\": {\"element-6066-11e4-a52e-4f735466cecf\": \"elem-777\"}}"}

                           (and (= method :post) (= url "http://localhost:9515/session/session-999/element/elem-777/click"))
                           {:status 200 :body "{\"value\": null}"}

                           (and (= method :post) (= url "http://localhost:9515/session/session-999/element/elem-777/value"))
                           {:status 200 :body "{\"value\": null}"}

                           (and (= method :delete) (= url "http://localhost:9515/session/session-999"))
                           {:status 200 :body "{\"value\": null}"}

                           :else
                           {:status 404 :body "{\"value\": {\"message\": \"Not Found\"}}"}))]
      (with-redefs [http/post (fn [url opts] (mock-http-fn :post url opts))
                    http/get (fn [url opts] (mock-http-fn :get url opts))
                    http/delete (fn [url opts] (mock-http-fn :delete url opts))]
        (let [session-id (wd/create-session base-url {:browserName "chrome"})
              _ (is (= "session-999" session-id))
              
              _ (wd/navigate base-url session-id "https://example.com")
              _ (is (some #(and (= (:method %) :post) (= (:url %) "http://localhost:9515/session/session-999/url")) @requests))
              
              title (wd/get-title base-url session-id)
              _ (is (= "Test Page Title" title))
              
              elem-id (wd/find-element base-url session-id "css selector" "h1")
              _ (is (= "elem-777" elem-id))
              
              _ (wd/click-element base-url session-id elem-id)
              _ (is (some #(and (= (:method %) :post) (= (:url %) "http://localhost:9515/session/session-999/element/elem-777/click")) @requests))
              
              _ (wd/send-keys base-url session-id elem-id "hello")
              _ (is (some #(and (= (:method %) :post) (= (:url %) "http://localhost:9515/session/session-999/element/elem-777/value")) @requests))
              
              closed (wd/close-session base-url session-id)
              _ (is closed)]
          (println "WebDriver Functional Pipeline Test: Passed."))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
