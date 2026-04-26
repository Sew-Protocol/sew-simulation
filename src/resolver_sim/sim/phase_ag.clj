(ns resolver-sim.sim.phase-ag
  "Phase AG: EMA Quality Signal Convergence (BM-05).

   Tests whether the exponential moving average (EMA) quality score used
   for resolver workload routing converges to an accurate signal within the
   documented 30-dispute window, and whether the cold-start initial score
   choice affects assignment access for new resolvers.

   Hypothesis A (convergence):
     With alpha=0.10 (emaAlphaBps=1000), the EMA score of a resolver with
     known accuracy p-correct converges to within 5% MAE of its steady-state
     value after ≤ 30 disputes for all p-correct in [0.60, 0.99].

   Hypothesis B (cold-start):
     A resolver initialised at score=0.0 (current implicit default) is locked
     out of assignments under minEmaScoreThreshold until enough disputes
     accrue, whereas score=0.5 (neutral) allows immediate access.
     The assignment gap = n-disputes until score crosses threshold.

   Parameters swept:
     :alpha-bps      — [500 1000 2000 3000]  (0.05, 0.10, 0.20, 0.30)
     :p-correct      — [0.60 0.70 0.80 0.85 0.90 0.99]
     :initial-score  — [0.0 0.5]             (cold-start vs neutral)
     :n-disputes     — 50 (enough to observe convergence)
     :n-sims         — 2000 (Monte Carlo runs per parameter set)

   Protocol constants (from DRMStorageBase.sol):
     emaAlphaBps            = 1000   (10%)
     minEmaScoreThreshold   = 500000 / 1e6 = 0.50  (normalised)"
  (:require [resolver-sim.model.rng :as rng]))

;; ---------------------------------------------------------------------------
;; Protocol constants
;; ---------------------------------------------------------------------------

(def DEFAULT_ALPHA_BPS      1000)     ; emaAlphaBps = 1000 → α = 0.10
(def MIN_SCORE_THRESHOLD    0.50)     ; minEmaScoreThreshold = 500000 / 1e6
(def CONVERGENCE_EPSILON    0.05)     ; 5% MAE target
(def CONVERGENCE_N_TARGET   30)       ; disputes before convergence expected

;; ---------------------------------------------------------------------------
;; EMA update rule (mirrors DRM state machine)
;; ---------------------------------------------------------------------------

(defn alpha [alpha-bps] (/ alpha-bps 10000.0))

(defn ema-update
  "Apply one EMA update given a binary outcome signal (1=correct, 0=reversed)."
  [score a signal]
  (+ (* (- 1.0 a) score) (* a signal)))

(defn ema-steady-state
  "EMA steady-state value for a resolver with known accuracy p-correct.

   signal = 1 with probability p-correct (correct decision, not reversed)
   signal = 0 with probability (1 - p-correct) (reversed on escalation)

   E[score] = p-correct × 1 + (1 - p-correct) × 0 = p-correct

   Note: the actual escalation rate modulates how often a signal fires.
   We simplify by treating every dispute as producing a signal."
  [p-correct]
  p-correct)

;; ---------------------------------------------------------------------------
;; Single resolver simulation: track EMA trajectory over n-disputes
;; ---------------------------------------------------------------------------

(defn simulate-resolver
  "Simulate n-disputes for one resolver with known p-correct.
   Returns the EMA score at each dispute step as a vector."
  [p-correct alpha-bps initial-score n-disputes d-rng]
  (let [a (alpha alpha-bps)]
    (loop [step    0
           score   initial-score
           history []]
      (if (= step n-disputes)
        history
        (let [correct? (< (rng/next-double d-rng) p-correct)
              signal   (if correct? 1.0 0.0)
              new-score (ema-update score a signal)]
          (recur (inc step) new-score (conj history new-score)))))))

;; ---------------------------------------------------------------------------
;; BM-05a: Convergence — MAE after n-disputes
;; ---------------------------------------------------------------------------

(defn run-convergence-trial
  "Run n-sims simulations and compute MAE of EMA at each dispute step."
  [p-correct alpha-bps initial-score n-disputes n-sims seed]
  (let [d-rng      (rng/make-rng seed)
        steady     (ema-steady-state p-correct)
        trajectories (doall
                      (for [_ (range n-sims)]
                        (simulate-resolver p-correct alpha-bps initial-score n-disputes d-rng)))
        ;; MAE at each step = mean |score - steady| across all sims
        mae-at-step (for [step (range n-disputes)]
                      (let [scores (map #(nth % step) trajectories)
                            errors (map #(Math/abs (- % steady)) scores)]
                        (/ (reduce + errors) n-sims)))]
    {:p-correct     p-correct
     :alpha-bps     alpha-bps
     :initial-score initial-score
     :steady-state  steady
     :mae-at-30     (nth (vec mae-at-step) (min (dec n-disputes) (dec CONVERGENCE_N_TARGET)))
     :mae-at-50     (last mae-at-step)
     :converged-at  (first (keep-indexed
                            (fn [i mae] (when (< mae CONVERGENCE_EPSILON) i))
                            mae-at-step))
     :pass?         (let [mae-30 (nth (vec mae-at-step)
                                      (min (dec n-disputes) (dec CONVERGENCE_N_TARGET)))]
                      (< mae-30 CONVERGENCE_EPSILON))}))

;; ---------------------------------------------------------------------------
;; BM-05b: Cold-start — disputes until score crosses assignment threshold
;; ---------------------------------------------------------------------------

(defn cold-start-gap
  "Number of disputes until EMA score first exceeds MIN_SCORE_THRESHOLD.
   Returns nil if it never crosses within n-disputes."
  [p-correct alpha-bps initial-score n-disputes d-rng]
  (let [a    (alpha alpha-bps)
        traj (simulate-resolver p-correct alpha-bps initial-score n-disputes d-rng)]
    (first (keep-indexed
            (fn [i score] (when (>= score MIN_SCORE_THRESHOLD) i))
            traj))))

(defn run-cold-start-comparison
  "Compare gap for score=0.0 vs score=0.5 at protocol defaults."
  [p-correct n-sims seed]
  (let [d-rng     (rng/make-rng seed)
        n-disputes 100
        gaps-zero (doall
                   (for [_ (range n-sims)]
                     (cold-start-gap p-correct DEFAULT_ALPHA_BPS 0.0 n-disputes d-rng)))
        gaps-half (doall
                   (for [_ (range n-sims)]
                     (cold-start-gap p-correct DEFAULT_ALPHA_BPS 0.5 n-disputes d-rng)))
        finite-zero (remove nil? gaps-zero)
        finite-half (remove nil? gaps-half)
        median-gap  (fn [coll]
                      (if (empty? coll)
                        n-disputes
                        (let [s (sort coll) n (count s)]
                          (nth s (int (/ n 2))))))]
    {:p-correct         p-correct
     :median-gap-zero   (median-gap finite-zero)
     :median-gap-half   (median-gap finite-half)
     :pct-locked-zero   (double (/ (count (filter nil? gaps-zero)) n-sims))
     :pct-locked-half   (double (/ (count (filter nil? gaps-half)) n-sims))
     :gap-reduction     (- (median-gap finite-zero) (median-gap finite-half))}))

;; ---------------------------------------------------------------------------
;; Sweep
;; ---------------------------------------------------------------------------

(defn run-convergence-sweep
  [{:keys [n-sims n-disputes base-seed]
    :or   {n-sims 2000 n-disputes 50 base-seed 42}}]
  (let [alpha-vals    [500 1000 2000 3000]
        p-vals        [0.60 0.70 0.80 0.85 0.90 0.99]
        initial-vals  [0.0 0.5]]
    (doall
     (for [a-bps alpha-vals
           p     p-vals
           init  initial-vals
           :let  [seed (+ base-seed (* a-bps 7) (int (* p 100)) (int (* init 10)))]]
       (run-convergence-trial p a-bps init n-disputes n-sims seed)))))

(defn run-cold-start-sweep
  [{:keys [n-sims base-seed]
    :or   {n-sims 2000 base-seed 42}}]
  (let [p-vals [0.60 0.70 0.80 0.85 0.90]]
    (doall
     (for [p p-vals
           :let [seed (+ base-seed (int (* p 100)))]]
       (run-cold-start-comparison p n-sims seed)))))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn summarize-convergence
  [results]
  (let [;; Hypothesis A: protocol default (alpha=1000, any p-correct, any init)
        protocol-default (filter #(= DEFAULT_ALPHA_BPS (:alpha-bps %)) results)
        pass-default     (count (filter :pass? protocol-default))
        total-default    (count protocol-default)
        worst            (apply max-key :mae-at-30 protocol-default)
        ;; Effect of initial score on convergence
        init-0           (filter #(= 0.0 (:initial-score %)) protocol-default)
        init-half        (filter #(= 0.5 (:initial-score %)) protocol-default)
        avg-mae-0        (if (seq init-0)
                           (/ (reduce + (map :mae-at-30 init-0)) (count init-0))
                           0.0)
        avg-mae-half     (if (seq init-half)
                           (/ (reduce + (map :mae-at-30 init-half)) (count init-half))
                           0.0)]
    {:total-scenarios    (count results)
     :protocol-default   {:total total-default :passing pass-default
                          :hypothesis-holds? (= pass-default total-default)}
     :worst-case         {:p-correct     (:p-correct worst)
                          :alpha-bps     (:alpha-bps worst)
                          :initial-score (:initial-score worst)
                          :mae-at-30     (:mae-at-30 worst)}
     :initial-score-effect {:avg-mae-init-0    avg-mae-0
                             :avg-mae-init-half avg-mae-half
                             :benefit-of-neutral (- avg-mae-0 avg-mae-half)}}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn run-phase-ag
  "BM-05: EMA quality signal convergence sweep.

   Tests Hypothesis A (convergence within 30 disputes) and
   Hypothesis B (cold-start assignment gap at score=0.0 vs score=0.5)."
  ([] (run-phase-ag {}))
  ([params]
   (println "\n📊 PHASE AG: EMA QUALITY SIGNAL CONVERGENCE (BM-05)")
   (println "   Hypothesis A: MAE < 5% after 30 disputes at protocol default (α=0.10)")
   (println "   Hypothesis B: score=0.0 cold-start causes assignment lockout; score=0.5 does not")
   (println (format "   Protocol default: alpha-bps=%d, threshold=%.2f"
                    DEFAULT_ALPHA_BPS MIN_SCORE_THRESHOLD))
   (println "")

   (println "   Running convergence sweep...")
   (let [conv-results (run-convergence-sweep params)
         summary      (summarize-convergence conv-results)]

     (println "   Protocol-default results (alpha=1000, init ∈ {0.0, 0.5}):")
     (println "   p-correct  init   MAE@30   converged-at  result")
     (println "   ──────────────────────────────────────────────────")
     (doseq [r (filter #(= DEFAULT_ALPHA_BPS (:alpha-bps %)) conv-results)]
       (println (format "   %-10.2f %-6.1f %-8.4f %-13s %s"
                        (:p-correct r)
                        (:initial-score r)
                        (:mae-at-30 r)
                        (if (:converged-at r) (str (:converged-at r) " disputes") "never@50")
                        (if (:pass? r) "✅" "❌"))))
     (println "")

     (println "   Running cold-start comparison...")
     (let [cs-results (run-cold-start-sweep params)]
       (println "   Cold-start assignment gap (disputes until score ≥ 0.50 threshold):")
       (println "   p-correct  gap@init=0.0  gap@init=0.5  reduction  pct-locked@0.0")
       (println "   ──────────────────────────────────────────────────────────────────")
       (doseq [r cs-results]
         (println (format "   %-10.2f %-13d %-13d %-10d %.0f%%"
                          (:p-correct r)
                          (:median-gap-zero r)
                          (:median-gap-half r)
                          (:gap-reduction r)
                          (* 100 (:pct-locked-zero r)))))
       (println "")

       (println "═══════════════════════════════════════════════════")
       (println "📋 PHASE AG SUMMARY")
       (println "═══════════════════════════════════════════════════")
       (let [pd (:protocol-default summary)
             cs-any-locked? (some #(> (:pct-locked-zero %) 0.10) cs-results)
             cs-half-clear? (every? #(< (:pct-locked-half %) 0.01) cs-results)]
         (println (format "   Convergence (Hypothesis A):"))
         (println (format "     Protocol-default configs passing: %d / %d"
                          (:passing pd) (:total pd)))
         (println (format "     Hypothesis A holds? %s"
                          (if (:hypothesis-holds? pd)
                            "✅ YES — EMA converges within 30 disputes"
                            "❌ NO  — convergence gap; consider higher alpha or smaller epsilon")))
         (println (format "     Worst case: p=%.2f init=%.1f MAE@30=%.4f"
                          (get-in summary [:worst-case :p-correct])
                          (get-in summary [:worst-case :initial-score])
                          (get-in summary [:worst-case :mae-at-30])))
         (println "")
         (println (format "   Cold-start (Hypothesis B):"))
         (println (format "     init=0.0 lockout rate > 10%%: %s"
                          (if cs-any-locked? "✅ CONFIRMED (lockout exists)" "— not observed")))
         (println (format "     init=0.5 lockout rate < 1%%: %s"
                          (if cs-half-clear? "✅ CONFIRMED (neutral init resolves gap)" "❌ still locked")))
         (println "")
         (if cs-any-locked?
           (do (println "   Recommendation: Default initial EMA score to 0.5 (neutral).")
               (println "   This eliminates cold-start assignment lockout with no accuracy cost."))
           (println "   Cold-start lockout not observed at this p-correct range."))
         (println "")

         {:convergence-summary summary
          :cold-start-results  (vec cs-results)
          :hypothesis-a-holds? (:hypothesis-holds? pd)
          :hypothesis-b-holds? (and cs-any-locked? cs-half-clear?)
          :recommendation      (when cs-any-locked?
                                 "Set initial EMA score to 0.5 (neutral) to prevent cold-start lockout")})))))
