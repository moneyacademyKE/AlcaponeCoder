(ns benchmark
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [agent]
            [config]
            [store]
            [registry]
            [memory]
            [tools.terminal]
            [tools.patch]
            [backend]
            [clojure.java.io :as io]))

(defn run-task [name instruction]
  (println (str "\n=== BENCHMARK TASK: " name " ==="))
  (println (str "Instruction: " instruction))
  (let [session-id (str "bench-" (java.util.UUID/randomUUID))
        state (atom {:cached-prompt nil})
        start-time (System/currentTimeMillis)]
    (let [res (agent/run-conversation session-id instruction state)
          end-time (System/currentTimeMillis)]
      (assoc res :duration (- end-time start-time)))))

(deftest test-comprehensive-bench
  (config/load-env)
  (let [cfg (config/load-config)]
    (store/init-db!)
    (let [env (backend/create-local-env)]
      (binding [registry/*config* cfg
                registry/*budget* (atom 100)
                registry/*depth* 1
                registry/*env* env]
        
        (testing "1. Exploration & Analysis"
          (let [res (run-task "Exploration" "Find all files in clj_agents/ containing the word 'register!' and list their names.")]
            (is (not (nil? (:final-response res))))
            (println "Duration:" (:duration res) "ms")
            (println "Result:" (:final-response res))))
        
        (testing "2. State Management"
          (let [res (run-task "Memory" "Add a memory entry 'Project uses Babashka with OpenRouter' and then read it back to confirm.")]
            (is (not (nil? (:final-response res))))
            (is (clojure.string/includes? (memory/render-entries (memory/load-memory "memory")) "OpenRouter"))
            (println "Duration:" (:duration res) "ms")))
        
        (testing "3. Problem Solving"
          (spit "broken_script.py" "print('Hello' + 123)")
          (let [res (run-task "Debug" "There is a broken_script.py in the current directory. Find the error and fix it so it prints 'Hello 123'.")]
            (is (not (nil? (:final-response res))))
            (is (= "Hello 123\n" (:out (backend/run-bash env "python3 broken_script.py"))))
            (println "Duration:" (:duration res) "ms"))
          (io/delete-file "broken_script.py" true))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-tests)]
    (if (pos? (+ (:fail results) (:error results)))
      (System/exit 1)
      (System/exit 0))))
