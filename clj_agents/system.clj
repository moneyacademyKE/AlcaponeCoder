(ns system
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [backend]
            [hooks]
            [registry]
            [memory]
            [skill]
            [delegation]
            [tools.terminal]
            [tools.browser]
            [tools.system-tools]
            [tools.patch]
            [tools.multimedia]
            [tools.xml]))

(defn- validate-system-key [k v]
  (case k
    :registry (when (not (map? v))
                (throw (AssertionError. (str "System key :registry must be a Map, got: " (type v)))))
    :budget (when (not (number? v))
              (throw (AssertionError. (str "System key :budget must be a Number, got: " (type v)))))
    :approvals (when (not (map? v))
                 (throw (AssertionError. (str "System key :approvals must be a Map, got: " (type v)))))
    :cron-jobs (when (not (map? v))
                 (throw (AssertionError. (str "System key :cron-jobs must be a Map, got: " (type v)))))
    :browser-process (when (not (or (nil? v) (instance? clojure.lang.Atom v)))
                       (throw (AssertionError. (str "System key :browser-process must be an Atom or nil, got: " (type v)))))
    nil))

(deftype ValidatedSystemMap [m]
  clojure.lang.IPersistentMap
  (assoc [_ k v]
    (validate-system-key k v)
    (ValidatedSystemMap. (assoc m k v)))
  (without [_ k]
    (ValidatedSystemMap. (dissoc m k)))

  clojure.lang.ILookup
  (valAt [_ k] (get m k))
  (valAt [_ k default] (get m k default))

  clojure.lang.IPersistentCollection
  (count [_] (count m))
  (seq [_] (seq m))
  (cons [_ o] (ValidatedSystemMap. (conj m o)))
  (empty [_] (ValidatedSystemMap. {}))
  (equiv [_ o] (and (instance? ValidatedSystemMap o) (= m (.-m ^ValidatedSystemMap o))))

  clojure.lang.Associative
  (containsKey [_ k] (contains? m k))
  (entryAt [_ k] (find m k))

  java.lang.Iterable
  (iterator [_] (.iterator m))

  clojure.lang.IFn
  (invoke [_ k] (get m k))
  (invoke [_ k default] (get m k default)))

(defn create-system [& {:keys [session-id config]}]
  (let [base {:id (or session-id (str (java.util.UUID/randomUUID)))
              :config (or config {})
              :budget (get-in config [:agent :max_turns] 90) ;; Value, not atom
              :depth 0
              :env (backend/create-env :local)
              :registry {}  ;; Plain map — not atom (pure system map pattern)
              :hooks {}
              :approvals {}
              :cron-jobs {}
              :skill-stats {}
              :state {:turns-since-memory 0
                      :iters-since-skill 0
                      :plan "No plan established yet."} ;; Pure string plan
              :browser-process (atom nil)}
        ;; Initialize with the Validated wrapper
        system (ValidatedSystemMap. base)]

    ;; Thread system through all register-tools calls (pure — each returns enriched system)
    (-> system
        (memory/register-tools)
        (skill/register-tools)
        (delegation/register-tools)
        (tools.terminal/register-tools)
        (tools.browser/register-tools)
        (tools.system-tools/register-tools)
        (tools.patch/register-tools)
        (tools.multimedia/register-tools)
        (tools.xml/register-tools))))

(defn cleanup [system]
  (println "[SYSTEM] Cleaning up resources...")
  (backend/cleanup (:env system))
  (when-let [p @(:browser-process system)]
    (println "[SYSTEM] Terminating browser daemon...")
    (.destroy p))
  (println "[SYSTEM] Cleanup complete."))
