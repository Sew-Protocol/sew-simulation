(ns resolver-sim.scenario.theory
  "Theory evaluator for CDRS v1.1 scenarios.

   Determines whether a theoretical claim is falsified by replay metrics.
   This namespace is pure — no I/O, no DB, no side effects.

   ## Active vs reserved theory fields

   ACTIVE (parsed and evaluated):
     :falsifies-if   — vector of {:metric :op :value} conditions; evaluated
                       against replay metrics to determine claim status.
     :claim-id       — keyword identifier; included in evidence output.
     :assumptions    — vector of keywords; recorded but not programmatically
                       enforced (used for documentation and future constraint
                       checking).

   METADATA (present in schema, recorded but not evaluated):
     :claim          — human-readable claim text; passed through to output.
     :game-class     — e.g. :repeated-stochastic-game; reserved for future
                       game-theoretic classification.
     :equilibrium-concept — e.g. [:subgame-perfect-equilibrium]; reserved.
     :mechanism-properties — e.g. [:incentive-compatibility]; reserved.
     :threat-model   — vector of threat actor descriptions; reserved.

   IGNORED (present in payoff-model, not evaluated here):
     :payoff-model/:tracked  — metric names to track across epochs.
     :payoff-model/:costs    — cost flags (slashing, gas, opportunity-cost).

   ## Full Schema Documentation

   See: docs/CDRS-v1.1-THEORY-SCHEMA.md for comprehensive field inventory,
   validation rules, examples by purpose, and future extensions."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Shared value helpers (also used by scenario.expectations)
;; ---------------------------------------------------------------------------

(defn normalize-val
  "Normalize a value to a string for non-numeric equality comparisons."
  [v]
  (cond
    (keyword? v) (name v)
    :else (str v)))

(defn to-kw
  "Coerce a value to a keyword, stripping any spurious leading colon."
  [x]
  (let [s  (if (keyword? x) (name x) (str x))
        s' (if (.startsWith s ":") (subs s 1) s)]
    (keyword s')))

(defn- try-number
  "Coerce value to a number (Long parse). Returns nil if not possible."
  [v]
  (cond
    (number? v) v
    (string? v) (try (Long/parseLong v) (catch Exception _ nil))
    :else nil))

;; ---------------------------------------------------------------------------
;; Metric operator evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-metric-op
  "Evaluate a metric operation with robust numeric comparison.
   Uses == for numeric types (handles Long/Integer/Double equivalence).
   Falls back to normalized string equality for non-numeric values."
  [op actual target]
  (let [op-kw      (to-kw op)
        num-actual (try-number actual)
        num-target (try-number target)]
    (case op-kw
      :=    (if (and num-actual num-target)
              (== num-actual num-target)
              (= (normalize-val actual) (normalize-val target)))
      :>    (if (and num-actual num-target) (> num-actual num-target) false)
      :<    (if (and num-actual num-target) (< num-actual num-target) false)
      :>=   (if (and num-actual num-target) (>= num-actual num-target) false)
      :<=   (if (and num-actual num-target) (<= num-actual num-target) false)
      :not= (not (if (and num-actual num-target)
                   (== num-actual num-target)
                   (= (normalize-val actual) (normalize-val target))))
      false)))

;; ---------------------------------------------------------------------------
;; Theory field consumption table
;;
;; This function reads the :theory map from a CDRS v1.1 scenario.
;; Not all schema fields drive evaluation — see the table below.
;;
;;   Field                   Status      Consumed by this fn?
;;   ──────────────────────────────────────────────────────────
;;   :claim-id               ACTIVE      Yes — included in evidence output
;;   :assumptions            ACTIVE      No  — recorded in schema; not enforced here
;;   :falsifies-if           ACTIVE      Yes — core falsification logic
;;   :claim                  METADATA    No  — human-readable text; passed through
;;   :game-class             RESERVED    No  — future game-theoretic classification
;;   :equilibrium-concept    RESERVED    No  — future equilibrium validation
;;   :mechanism-properties   RESERVED    No  — future mechanism property checks
;;   :threat-model           RESERVED    No  — documentation only
;;
;;   :payoff-model/*         IGNORED     No  — belongs to multi-epoch layer, not here
;;
;; If you add :game-class or :equilibrium-concept to a scenario, those fields
;; will be accepted and stored but will not affect evaluation results.
;; To make them actionable, extend this function and update the docs.
;;
;; See: docs/CDRS-v1.1-THEORY-SCHEMA.md for the full schema reference.
;; ---------------------------------------------------------------------------

(defn evaluate-theory
  "Determine if a theoretical claim is falsified by observed metrics.
   Returns {:status kw :falsified? bool :evidence [v-map]}.

   Fields consumed from the theory map: :claim-id, :falsifies-if.
   All other theory fields (see comment block above) are accepted but not evaluated.

   :status is one of:
     :not-evaluated  — no theory block provided
     :not-falsified  — claim held; no falsification condition triggered
     :falsified      — at least one falsifies-if condition was met
     :inconclusive   — none of the tracked metrics were present in the result"
  [result theory]
  (if (nil? theory)
    {:status :not-evaluated :falsified? false :evidence []}
    (let [metrics  (:metrics result)
          conds    (:falsifies-if theory)
          all-nil? (every? #(nil? (get metrics (to-kw (:metric %)))) conds)
          falsified (atom [])]
      (doseq [f conds]
        (let [actual (get metrics (to-kw (:metric f)))]
          (when (evaluate-metric-op (:op f) actual (:value f))
            (swap! falsified conj {:metric (:metric f) :op (:op f) :value (:value f) :actual actual}))))
      (let [status (cond
                     (seq @falsified) :falsified
                     all-nil?         :inconclusive
                     :else            :not-falsified)]
        {:status     status
         :falsified? (= status :falsified)
         :evidence   @falsified}))))
