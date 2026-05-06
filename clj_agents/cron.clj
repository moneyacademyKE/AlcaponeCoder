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

(defn parse-schedule [expr now]
  (let [expr (str/trim expr)]
    (cond
      (str/starts-with? expr "every ")
      [(+ now (* 1000 (parse-duration (subs expr 6)))) false]
      
      (re-matches #"\d+[smhd]" expr)
      [(+ now (* 1000 (parse-duration expr))) true]
      
      :else ;; Default to 1 min for unknown/cron-lite
      [(+ now 60000) false])))

(defn add-job [job]
  (fn [system]
    (assoc-in system [:cron-jobs (:job-id job)] job)))

(defn remove-job [id]
  (fn [system]
    (update system :cron-jobs dissoc id)))

(defn add-job-tool [system job-args]
  (let [now (System/currentTimeMillis)
        [next-fire one-shot] (parse-schedule (:schedule job-args) now)
        job (assoc (map->CronJob job-args) :next-fire next-fire :one-shot one-shot)]
    {:result "Job added" :system-update (add-job job)}))

(defn remove-job-tool [system id]
  {:result "Job removed" :system-update (remove-job id)})

(defn get-due-jobs [system now]
  (let [jobs (get system :cron-jobs {})]
    (filter (fn [j] (>= now (:next-fire j))) (vals jobs))))

(defn advance-job [job now]
  (if (:one-shot job)
    (remove-job (:job-id job))
    (let [[next-fire _] (parse-schedule (:schedule job) now)]
      (add-job (assoc job :next-fire next-fire)))))
