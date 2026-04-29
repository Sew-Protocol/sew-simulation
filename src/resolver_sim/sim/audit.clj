(ns resolver-sim.sim.audit
  "Multi-epoch stability audit: falsifiable hypothesis checks.

   The core question is not 'does honest beat malicious on average?' —
   the epoch-level batch results already show that. The question is:

     Can a malicious resolver survive, compound, or become dominant over time?

   analyze-multi-epoch answers this with a composite of absolute, distributional,
   and trajectory-slope checks.

   Layering: sim/* only. No db/*, io/* imports."
  (:require [resolver-sim.sim.trajectory :as trajectory]))

;; ---------------------------------------------------------------------------
;; Utility
;; ---------------------------------------------------------------------------

(defn- safe-div [n d] (if (zero? d) 0.0 (double (/ n d))))

(defn- mean-of [coll]
  (if (empty? coll) 0.0 (double (/ (reduce + 0.0 coll) (count coll)))))

(defn- percentile
  "p in [0,1]. Returns value at p-th percentile of coll."
  [coll p]
  (if (empty? coll)
    0.0
    (let [sorted (sort coll)
          idx    (min (dec (count sorted))
                      (int (Math/floor (* p (count sorted)))))]
      (double (nth sorted idx)))))

(defn- linear-slope
  "Least-squares slope of a numeric series (x = index, y = value).
   Returns positive value when y is trending upward."
  [ys]
  (let [n (count ys)]
    (if (< n 2)
      0.0
      (let [xs    (range n)
            xmean (mean-of xs)
            ymean (mean-of ys)
            numer (reduce + 0.0 (map (fn [x y] (* (- x xmean) (- y ymean))) xs ys))
            denom (reduce + 0.0 (map (fn [x] (Math/pow (- x xmean) 2)) xs))]
        (if (zero? denom) 0.0 (double (/ numer denom)))))))

;; ---------------------------------------------------------------------------
;; Per-resolver equity helpers
;; ---------------------------------------------------------------------------

(defn- resolver-final-equity [full-trajectories resolver-id]
  (-> (get full-trajectories resolver-id)
      :equity
      last
      (or 0.0)))

(defn- stratify
  "Partition resolver-ids by strategy from resolver-histories."
  [resolver-histories]
  (reduce-kv (fn [acc id r]
               (let [s (:strategy r :unknown)]
                 (update acc s (fnil conj []) id)))
             {} resolver-histories))

;; ---------------------------------------------------------------------------
;; Dominance-ratio trajectory check
;; ---------------------------------------------------------------------------

(defn- dominance-ratio-trajectory
  "Return vector of dominance ratios from epoch-results."
  [epoch-results]
  (mapv :dominance-ratio epoch-results))

(defn- malice-slope-improving?
  "True when the dominance ratio is trending downward (malice gaining ground)
   in the final tail-fraction of epochs. A positive slope on dominance means
   honest is pulling ahead; negative means malice is closing the gap.

   tail-fraction — fraction of epochs to use for the regression (default 0.2)."
  [epoch-results tail-fraction]
  (let [ratios (dominance-ratio-trajectory epoch-results)
        n      (count ratios)
        tail-n (max 2 (int (* tail-fraction n)))
        tail   (subvec (vec ratios) (- n tail-n))]
    (< (linear-slope tail) 0.0)))

;; ---------------------------------------------------------------------------
;; Main audit function
;; ---------------------------------------------------------------------------

(defn analyze-multi-epoch
  "Run the composite multi-epoch stability hypothesis against simulation output.

   result        — return value of run-multi-epoch
   opts          — optional overrides:
     :dominance-threshold     minimum honest/malice ratio (default 1.2)
     :max-malice-equity-share maximum fraction of total equity malice may hold (default 0.3)
     :tail-fraction           fraction of epochs used for slope regression (default 0.2)

   The hypothesis being tested:
     H: A malicious resolver cannot survive, compound, or dominate over time.

   Returns:
   {:hypothesis     :malice-cannot-dominate-over-time
    :result         :pass | :fail
    :epochs-checked N
    :checks         {:honest-mean-positive?            bool
                     :malice-mean-nonpositive?          bool
                     :dominance-above-threshold?        bool
                     :honest-p10-above-malice-p90?      bool
                     :malice-equity-share-below-limit?  bool
                     :malice-slope-not-improving?       bool
                     :malice-survival-rate-low?         bool}
    :summary        {:min-dominance-ratio    N
                     :final-honest-p10       N
                     :final-malice-p90       N
                     :malice-equity-share    N
                     :malice-survival-rate   N
                     :dominance-slope        N (positive = honest pulling ahead)}
    :violations     [{:check kw :epoch N :value V}]
    :epoch-results  [...]}"
  ([result] (analyze-multi-epoch result {}))
  ([result opts]
   (let [{:keys [epoch-results aggregated-stats full-trajectories resolver-histories]} result
         n-epochs          (count epoch-results)
         dom-threshold     (get opts :dominance-threshold 1.2)
         max-equity-share  (get opts :max-malice-equity-share 0.3)
         tail-fraction     (get opts :tail-fraction 0.2)

         strata          (stratify resolver-histories)
         honest-ids      (get strata :honest [])
         malice-ids      (concat (get strata :malicious [])
                                 (get strata :lazy [])
                                 (get strata :collusive []))

         honest-equities (mapv #(resolver-final-equity full-trajectories %) honest-ids)
         malice-equities (mapv #(resolver-final-equity full-trajectories %) malice-ids)

         total-equity    (+ (apply + 0.0 honest-equities) (apply + 0.0 malice-equities))
         malice-equity   (apply + 0.0 malice-equities)

         dom-ratios      (dominance-ratio-trajectory epoch-results)
         min-dominance   (if (seq dom-ratios) (apply min dom-ratios) 0.0)
         dom-slope       (linear-slope dom-ratios)

         honest-p10      (percentile honest-equities 0.10)
         malice-p90      (percentile malice-equities 0.90)

         equity-share    (safe-div malice-equity (max 1.0 total-equity))
         survival-rate   (:malice-survival-rate aggregated-stats 0.0)

         ;; Epoch-level check: any epoch where dominance fell below threshold
         dominance-violations
         (for [[e r] (map-indexed vector epoch-results)
               :when (< (:dominance-ratio r 0.0) dom-threshold)]
           {:check :dominance-above-threshold?
            :epoch (inc e)
            :value (:dominance-ratio r)})

         checks
         {:honest-mean-positive?           (pos? (:honest-cumulative-profit aggregated-stats 0.0))
          :malice-mean-nonpositive?         (<= (:malice-cumulative-profit aggregated-stats 0.0) 0.0)
          :dominance-above-threshold?       (>= min-dominance dom-threshold)
          :honest-p10-above-malice-p90?     (> honest-p10 malice-p90)
          :malice-equity-share-below-limit? (< equity-share max-equity-share)
          :malice-slope-not-improving?      (not (malice-slope-improving? epoch-results tail-fraction))
          :malice-survival-rate-low?        (< survival-rate 0.5)}

         all-violations
         (concat dominance-violations
                 (for [[check passed?] checks
                       :when (not passed?)]
                   {:check check :value (get checks check)}))

         passed? (empty? all-violations)]

     {:hypothesis     :malice-cannot-dominate-over-time
      :result         (if passed? :pass :fail)
      :epochs-checked n-epochs
      :checks         checks
      :summary        {:min-dominance-ratio  min-dominance
                       :dominance-slope      dom-slope
                       :final-honest-p10     honest-p10
                       :final-malice-p90     malice-p90
                       :malice-equity-share  equity-share
                       :malice-survival-rate survival-rate}
      :violations     (vec all-violations)
      :epoch-results  epoch-results})))

;; ---------------------------------------------------------------------------
;; Reproducibility manifest
;; ---------------------------------------------------------------------------

(defn make-manifest
  "Build a reproducibility manifest for a completed multi-epoch run.

   result      — return value of run-multi-epoch
   params-file — path to the params EDN file used
   seed        — the RNG seed used
   git-commit  — current git HEAD SHA (optional)

   Returns a map suitable for writing to EDN alongside results."
  [result params-file seed & {:keys [git-commit sim-version]}]
  {:params-file    params-file
   :seed           seed
   :epochs         (:n-epochs result)
   :trials-per-epoch (:n-trials-per-epoch result)
   :initial-resolvers (:initial-resolver-count result)
   :routing-mode   (-> result :epoch-results first :routing-mode)
   :git-commit     (or git-commit "unknown")
   :sim-version    (or sim-version "0.1.0")
   :completed-at   (str (java.time.Instant/now))})