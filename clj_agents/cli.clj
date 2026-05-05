(ns cli
  (:require [clojure.string :as str]))

(def commands (atom {}))

(defn register-command! [name description handler]
  (swap! commands assoc name {:description description :handler handler}))

(defn dispatch-command [input]
  (let [[cmd & args] (str/split (subs input 1) #"\s+")
        found (get @commands cmd)]
    (if found
      ((:handler found) args)
      (println (str "Unknown command: " cmd)))))

(defn render-stream [delta]
  (if (nil? delta)
    (println)
    (do (print delta) (flush))))

(defn show-status [text]
  ;; Simple ANSI escape to show status at top of terminal (if supported)
  ;; For simplicity, we just print a special line
  (println (str "\r[STATUS] " text)))

(register-command! "help" "Show help" (fn [_] (doseq [[k v] @commands] (println (str "/" k " - " (:description v))))))
(register-command! "exit" "Exit" (fn [_] (System/exit 0)))
