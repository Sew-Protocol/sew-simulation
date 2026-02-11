(ns resolver-sim.model.types
  "Core parameter schemas and types for the dispute resolution simulation."
  (:refer-clojure :exclude []))

;; Scenario configuration schema
(def scenario-schema
  {:description string?
   :scenario-id string?
   :rng-seed integer?
   :escrow-distribution map?
   :strategy-mix (fn [m] (and (map? m)
                               (every? #(>= (get m % 0) 0) [:honest :lazy :malicious :collusive])
                               (let [sum (reduce + (vals m))]
                                 (or (== sum 1.0) (== sum 1)))))
   :resolver-fee-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
   :appeal-bond-bps (fn [x] (and (number? x) (>= x 0) (<= x 10000)))
   :slash-multiplier (fn [x] (and (number? x) (> x 0)))
   :appeal-probability-if-correct (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :appeal-probability-if-wrong (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :slashing-detection-probability (fn [x] (and (number? x) (>= x 0) (<= x 1)))
   :n-trials (fn [x] (and (integer? x) (> x 0)))
   :n-seeds (fn [x] (and (integer? x) (> x 0)))
   :parallelism (fn [x] (or (keyword? x) (integer? x)))})

;; Trial outcome record
(defrecord TrialOutcome
  [resolver-strategy
   dispute-correct?
   appeal-triggered?
   slashed?
   honest-profit
   malice-profit
   fee-earned
   slashing-loss])

;; Batch aggregation record
(defrecord BatchSummary
  [n-trials
   mean-honest
   mean-malice
   std-honest
   std-malice
   appeal-rate
   slash-rate
   honest-wins-fraction
   collusion-success-rate])

;; Run metadata record
(defrecord RunMetadata
  [scenario-id
   git-commit
   git-dirty?
   jvm-version
   timestamp
   seed
   params])

;; Defaults
(def default-params
  {:resolver-fee-bps 150
   :appeal-bond-bps 700
   :slash-multiplier 2.5
   :appeal-probability-if-correct 0.05
   :appeal-probability-if-wrong 0.40
   :slashing-detection-probability 0.10
   :n-trials 1000
   :n-seeds 1
   :parallelism :auto})

(defn validate-scenario
  "Validate scenario params against schema. Throws if invalid."
  [scenario]
  (doseq [[k validator] scenario-schema]
    (if-let [v (get scenario k)]
      (when-not (validator v)
        (throw (ex-info (format "Invalid param %s: %s" k v) {:param k :value v})))
      (when (not (some #(= k %) [:sweep-params :attacker-extra-capital-multiplier :resolver-bond-bps]))
        (throw (ex-info (format "Missing required param %s" k) {:param k})))))
  scenario)
