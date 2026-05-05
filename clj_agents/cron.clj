(ns cron
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.time LocalDateTime Duration]
           [java.time.format DateTimeFormatter]))

(defrecord CronJob [job-id schedule prompt session-key next-fire one-shot])

(defn parse-duration [s]
  (let [unit (last s)
        num (read-string (subs s 0 (dec (count s))))]
    (case unit
      \s num
      \m (* num 60)
      \h (* num 3600)
      \d (* num 86400)
      (throw (Exception. (str "Unknown unit: " unit))))))

(defn parse-schedule [expr]
  (let [now (System/currentTimeMillis)
        expr (str/trim expr)]
    (cond
      (str/starts-with? expr "every ")
      [(+ now (* 1000 (parse-duration (subs expr 6)))) false]
      
      (re-matches #"\d+[smhd]" expr)
      [(+ now (* 1000 (parse-duration expr))) true]
      
      :else ;; Default to 1 min for unknown/cron-lite
      [(+ now 60000) false])))

(defonce job-store (atom {})) ;; id -> CronJob

(defn load-jobs! []
  (let [f (io/file "jobs.json")]
    (when (.exists f)
      (let [data (json/parse-string (slurp f) true)]
        (reset! job-store (into {} (for [j data] [(:job-id j) (map->CronJob j)])))))))

(defn save-jobs! []
  (spit "jobs.json" (json/generate-string (vals @job-store) {:pretty true})))

(defn add-job! [job]
  (swap! job-store assoc (:job-id job) job)
  (save-jobs!))

(defn remove-job! [id]
  (swap! job-store dissoc id)
  (save-jobs!))

(defn get-due-jobs []
  (let [now (System/currentTimeMillis)]
    (filter (fn [j] (>= now (:next-fire j))) (vals @job-store))))

(defn advance-job! [job]
  (if (:one-shot job)
    (remove-job! (:job-id job))
    (let [[next-fire _] (parse-schedule (:schedule job))]
      (add-job! (assoc job :next-fire next-fire)))))

(defn start-scheduler! [fire-callback]
  (future
    (loop []
      (let [due (get-due-jobs)]
        (doseq [job due]
          (try
            (fire-callback job)
            (catch Exception e (println "Job failed:" (ex-message e))))
          (advance-job! job))
        (Thread/sleep 30000)
        (recur)))))
