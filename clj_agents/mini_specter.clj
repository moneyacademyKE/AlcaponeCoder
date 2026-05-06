(ns mini-specter)

;; Lightweight vended implementation of Specter-like path transformations
;; for environments without Java/Maven (Babashka).
;; Aligns with Pattern 16: Pure Data Pipeline.

(def ALL :mini-specter/all)
(def MAP-VALS :mini-specter/map-vals)

(defn- walk-path [data [p & ps] f]
  (if (nil? p)
    (f data)
    (cond
      (= p ALL)
      (if (vector? data)
        (mapv #(walk-path % ps f) data)
        data)

      (= p MAP-VALS)
      (if (map? data)
        (into {} (for [[k v] data] [k (walk-path v ps f)]))
        data)

      (map? data)
      (if (contains? data p)
        (update data p #(walk-path % ps f))
        data)

      (vector? data)
      (if (number? p)
        (update data p #(walk-path % ps f))
        data)

      :else data)))

(defn transform [path f data]
  "Transforms 'data' at 'path' using function 'f'. 
   Path can include keys, indices, ALL (for vectors), or MAP-VALS (for maps)."
  (walk-path data path f))

(defn select [path data]
  "Returns a vector of values matched by 'path' in 'data'."
  (let [res (atom [])]
    (walk-path data path (fn [v] (swap! res conj v) v))
    @res))

(defn setval [path val data]
  "Sets values at 'path' in 'data' to 'val'."
  (transform path (constantly val) data))
