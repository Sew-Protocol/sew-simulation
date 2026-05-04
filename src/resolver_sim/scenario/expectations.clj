(ns resolver-sim.scenario.expectations
  "Expectation evaluator for CDRS v1.1 scenarios.

   Checks execution-level pass/fail criteria: named invariants, terminal world
   state, and metric assertions. This namespace is pure — no I/O, no DB.

   Depends on resolver-sim.scenario.theory for shared value helpers and
   evaluate-metric-op. The split is intentional: theory evaluation (claim
   falsification) and expectation evaluation (execution correctness) are
   separate concerns that may have different failure semantics."
  (:require [resolver-sim.scenario.theory :as theory]))

;; ---------------------------------------------------------------------------
;; Key-relaxed world lookup
;; ---------------------------------------------------------------------------

(defn- try-parse-int [k]
  (if (string? k)
    (try (Integer/parseInt k) (catch Exception _ nil))
    k))

(defn- get-relaxed
  "Get key k from map m, tolerating integer/string key type mismatch.
   Tries: exact key → integer parse → keyword parse → string of keyword."
  [m k]
  (let [int-k (try-parse-int k)
        kw-k  (when (string? k) (keyword k))]
    (cond
      (contains? m k)                          (get m k)
      (and int-k (contains? m int-k))          (get m int-k)
      (and kw-k (contains? m kw-k))            (get m kw-k)
      (and (keyword? k) (contains? m (name k))) (get m (name k))
      :else                                    (get m k))))

(defn- get-in-relaxed [m path]
  (reduce get-relaxed m path))

;; ---------------------------------------------------------------------------
;; Invariant evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-invariants
  "Check named invariants from :expectations/:invariants against the replay result.

   Lookup strategy:
   1. Per-invariant map: result[:metrics :invariant-results] is a map of
      {inv-kw :fail} for any invariant that violated at least once.
      If the named invariant is present → :fail.
      If it is absent from the map AND violations = 0 → :pass.
      If it is absent AND violations > 0 → conservative fail (another
      invariant fired but this named one is unconfirmed).
   2. Aggregate fallback: if :invariant-violations > 0 and the named
      invariant is not in the per-invariant map, fail conservatively —
      we know something broke but can't confirm which invariant.
   3. Pass: :invariant-violations = 0 means no invariant fired; all
      named invariants pass.

   Returns {:ok? bool :violations [v-map]}"
  [result named-invariants]
  (if (empty? named-invariants)
    {:ok? true :violations []}
    (let [inv-map        (get-in result [:metrics :invariant-results] {})
          agg-violations (get-in result [:metrics :invariant-violations] 0)
          violations     (atom [])]
      (doseq [inv-name named-invariants]
        (let [inv-kw (if (keyword? inv-name) inv-name (keyword (str inv-name)))
              status (cond
                       ;; Named result available — precise
                       (contains? inv-map inv-kw)
                       (get inv-map inv-kw)

                       ;; Not in per-invariant map; use aggregate fallback
                       (pos? agg-violations)
                       :fail

                       :else :pass)]
          (when (= status :fail)
            (swap! violations conj
                   {:type      :invariant-failed
                    :invariant inv-kw
                    :note      (if (contains? inv-map inv-kw)
                                 "per-invariant result: fail"
                                 (str "aggregate fallback: "
                                      agg-violations " violation(s) in run"))}))))
      {:ok? (empty? @violations)
       :violations @violations})))

;; ---------------------------------------------------------------------------
;; Expectations evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-expectations
  "Evaluate execution-level pass/fail criteria from an :expectations block.

   Checks in order:
     0. :invariants — named invariant checks (aggregate fallback if no per-invariant map)
     1. :terminal   — world state at end of trace (path + expected value)
     2. :metrics    — named metric assertions (op + value)

   Returns {:ok? bool :violations [v-map]}"
  [result expectations]
  (let [metrics    (:metrics result)
        trace      (:trace result)
        last-world (get (last trace) :world)
        violations (atom [])]

    ;; 0. Named invariant checks
    (let [inv-res (evaluate-invariants result (:invariants expectations []))]
      (when-not (:ok? inv-res)
        (doseq [v (:violations inv-res)]
          (swap! violations conj v))))

    ;; 1. Terminal state checks
    (doseq [t (:terminal expectations)]
      (let [actual (get-in-relaxed last-world (:path t))]
        (when-not (= (theory/normalize-val actual) (theory/normalize-val (:equals t)))
          (swap! violations conj {:type     :terminal-mismatch
                                  :path     (:path t)
                                  :expected (:equals t)
                                  :actual   actual}))))

    ;; 2. Metric expectations
    (doseq [m (:metrics expectations)]
      (let [actual (get metrics (theory/to-kw (:name m)))]
        (when-not (theory/evaluate-metric-op (:op m) actual (:value m))
          (swap! violations conj {:type     :metric-violation
                                  :name     (:name m)
                                  :op       (:op m)
                                  :expected (:value m)
                                  :actual   actual}))))

    {:ok? (empty? @violations)
     :violations @violations}))
