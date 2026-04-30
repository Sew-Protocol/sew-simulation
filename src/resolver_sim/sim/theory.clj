(ns resolver-sim.sim.theory
  "Evaluator for CDRS v1.1 theoretical robustness and expectations."
  (:require [clojure.string :as str]))

(defn- normalize-val [v]
  (cond
    (keyword? v) (name v)
    :else (str v)))

(defn- to-kw [x]
  (let [s (if (keyword? x) (name x) (str x))
         s' (if (.startsWith s ":") (subs s 1) s)]
    (keyword s')))

(defn- try-number [v]
  "Attempt to coerce value to a number. Returns nil if not possible."
  (cond
    (number? v) v
    (string? v) (try (Long/parseLong v) (catch Exception _ nil))
    :else nil))

(defn evaluate-metric-op [op actual target]
  "Evaluate a metric operation with robust numeric comparison.
   Handles numeric coercion and string comparisons gracefully."
  (let [op-kw (to-kw op)
        num-actual (try-number actual)
        num-target (try-number target)]
    (case op-kw
      :=  (if (and num-actual num-target) 
            (== num-actual num-target) 
            (= (normalize-val actual) (normalize-val target)))
      :>  (if (and num-actual num-target) (> num-actual num-target) false)
      :<  (if (and num-actual num-target) (< num-actual num-target) false)
      :>= (if (and num-actual num-target) (>= num-actual num-target) false)
      :<= (if (and num-actual num-target) (<= num-actual num-target) false)
      :not= (not (if (and num-actual num-target) 
                    (== num-actual num-target) 
                    (= (normalize-val actual) (normalize-val target))))
      false)))

(defn- try-parse-int [k]
  (if (string? k)
    (try (Integer/parseInt k) (catch Exception _ nil))
    k))

(defn- get-relaxed [m k]
  (let [int-k (try-parse-int k)
        kw-k  (when (string? k) (keyword k))]
    (cond
      (contains? m k) (get m k)
      (and int-k (contains? m int-k)) (get m int-k)
      (and kw-k (contains? m kw-k)) (get m kw-k)
      ;; Handle string keys for keyword lookups
      (and (keyword? k) (contains? m (name k))) (get m (name k))
      :else (get m k))))

(defn- get-in-relaxed [m path]
  (reduce get-relaxed m path))

(defn evaluate-expectations
  "Evaluate execution-level pass/fail criteria from :expectations block.
   Returns {:ok? bool :violations [v-map]}."
  [result expectations]
  (let [metrics (:metrics result)
        trace   (:trace result)
        last-world (get (last trace) :world)
        violations (atom [])]
    
    ;; 1. Terminal state checks
    (doseq [t (:terminal expectations)]
      (let [actual (get-in-relaxed last-world (:path t))]
        (when-not (= (normalize-val actual) (normalize-val (:equals t)))
          (swap! violations conj {:type :terminal-mismatch :path (:path t) :expected (:equals t) :actual actual}))))
    
    ;; 2. Metric expectations
    (doseq [m (:metrics expectations)]
      (let [actual (get metrics (to-kw (:name m)))]
        (when-not (evaluate-metric-op (:op m) actual (:value m))
          (swap! violations conj {:type :metric-violation :name (:name m) :op (:op m) :expected (:value m) :actual actual}))))
    
    {:ok? (empty? @violations)
     :violations @violations}))

(defn evaluate-theory
  "Determine if a theoretical claim is falsified by observed metrics.
   Returns {:falsified? bool :evidence [v-map]}."
  [result theory]
  (let [metrics (:metrics result)
        falsified (atom [])]
    (doseq [f (:falsifies-if theory)]
      (let [actual (get metrics (to-kw (:metric f)))]
        (when (evaluate-metric-op (:op f) actual (:value f))
          (swap! falsified conj {:type :claim-falsified :metric (:metric f) :op (:op f) :value (:value f) :actual actual}))))
    
    {:falsified? (not (empty? @falsified))
     :evidence @falsified}))
