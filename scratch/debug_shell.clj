(require '[babashka.process :refer [shell]])

(println "Testing rm...")
(try
  (shell ["/bin/rm" "--version"])
  (println "rm OK")
  (catch Exception e (println "rm failed:" (.getMessage e))))

(println "Testing sqlite3...")
(try
  (shell ["/usr/bin/sqlite3" "--version"])
  (println "sqlite3 OK")
  (catch Exception e (println "sqlite3 failed:" (.getMessage e))))
