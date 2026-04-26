(ns resolver-sim.sim.phase-af
  "Phase AF: Slashing Epoch Solvency (BM-04).

   Tests whether the insurance pool remains solvent across all realistic
   resolver-population and bond configurations when a worst-case epoch
   occurs — i.e. every resolver is slashed to the epoch cap simultaneously.

   Hypothesis:
     For any (n-resolvers, avg-bond-usd, pool-seed-usd) in the design
     envelope, insurance pool solvency-ratio ≥ 1.0 after a worst-case epoch.

   Parameters swept:
     :n-resolvers     — [5 10 20 50]
     :avg-bond-usd    — [250 500 1000 2500]  (bounded by MIN_RESOLVER_BOND=$250)
     :pool-seed-usd   — [0 500 1000 5000 10000]

   Protocol constants (from ResolverSlashingModuleV1 + ResolverStakingModuleV1):
     EPOCH_CAP_BPS          = 2000  (20% resolver epoch cap)
     SENIOR_EPOCH_CAP_BPS   = 1000  (10% senior epoch cap)
     INSURANCE_CUT_BPS      = 2000  (20% of each slash goes to insurance)

   Pass threshold: solvency-ratio ≥ 1.0 for all configurations in the
   design envelope (n-resolvers ≤ 20, avg-bond ≥ 500)."
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.sim.waterfall :as waterfall]))

;; ---------------------------------------------------------------------------
;; Protocol constants (mirrors ResolverSlashingModuleV1.sol)
;; ---------------------------------------------------------------------------

(def EPOCH_CAP_BPS         2000)   ; 20% per resolver per epoch
(def SENIOR_EPOCH_CAP_BPS  1000)   ; 10% per senior per epoch
(def INSURANCE_CUT_BPS     2000)   ; 20% of slash → insurance pool
(def RESOLVER_SLASH_BPS    200)    ; 2% per-slash rate (PENALTY_MISSED_RESOLVE)
(def MAX_ESCROW_PER_CASE   2000)   ; $2,000 hard cap
(def COVERAGE_MULTIPLIER   3)      ; M = 3× senior coverage requirement

;; ---------------------------------------------------------------------------
;; Core solvency calculation (analytical, no RNG needed)
;; ---------------------------------------------------------------------------

(defn max-epoch-drawdown
  "Compute the maximum insurance pool drawdown in a single worst-case epoch.

   Worst case: every resolver is slashed to the epoch cap (20% of bond).
   Of each slash, 20% goes to the insurance pool as incoming funding.
   The pool's net obligation is: claims it must pay out - incoming slash cuts.

   In DR v3, the insurance pool covers user losses when a resolver bond is
   depleted and cannot fully compensate a harmed party. In the worst case,
   every resolver is both slashed AND has an open user claim equal to the
   max escrow per case.

   Returns a map with the analytical drawdown components."
  [n-resolvers avg-bond-usd]
  (let [epoch-cap-rate   (/ EPOCH_CAP_BPS 10000.0)
        insurance-rate   (/ INSURANCE_CUT_BPS 10000.0)

        ;; Total slashed across all resolvers in one epoch
        total-slashed    (* n-resolvers avg-bond-usd epoch-cap-rate)

        ;; Insurance receives a cut of incoming slashes
        incoming-to-pool (* total-slashed insurance-rate)

        ;; Worst-case user claims: every resolver has a user claim equal to
        ;; max-escrow-per-case. If resolver bond < claim, insurance covers gap.
        ;; Worst case: resolver bond = avg-bond, claim = MAX_ESCROW_PER_CASE.
        ;; After slashing, resolver has (1 - epoch_cap) × bond remaining.
        ;; But user claim was placed BEFORE the slash, so full bond is available.
        ;; Net gap per resolver = max(0, MAX_ESCROW_PER_CASE - avg-bond)
        per-resolver-gap (max 0 (- MAX_ESCROW_PER_CASE avg-bond-usd))
        total-user-gaps  (* n-resolvers per-resolver-gap)

        ;; Net pool obligation = user gaps - incoming slash cuts
        ;; (insurance cuts arrive before payouts in happy path, but
        ;;  in a simultaneous crash we model them as arriving after)
        net-obligation   (max 0 (- total-user-gaps incoming-to-pool))]

    {:n-resolvers        n-resolvers
     :avg-bond-usd       avg-bond-usd
     :epoch-cap-rate     epoch-cap-rate
     :total-slashed      total-slashed
     :incoming-to-pool   incoming-to-pool
     :per-resolver-gap   per-resolver-gap
     :total-user-gaps    total-user-gaps
     :net-obligation     net-obligation}))

(defn solvency-ratio
  "pool-seed-usd / net-obligation. Infinity when obligation is zero (trivially solvent)."
  [pool-seed-usd net-obligation]
  (if (zero? net-obligation)
    ##Inf
    (/ (double pool-seed-usd) (double net-obligation))))

;; ---------------------------------------------------------------------------
;; Monte Carlo: add variance from lognormal bond distribution
;; ---------------------------------------------------------------------------

(defn simulate-epoch-solvency
  "Simulate one epoch with N resolvers drawn from a lognormal bond distribution.

   Resolvers with bond < MIN_RESOLVER_BOND are excluded (they cannot participate).
   Each resolver is slashed a random amount up to the epoch cap.
   Returns {:solvency-ratio float :pool-balance-after float :pass? bool}"
  [{:keys [n-resolvers avg-bond-usd pool-seed-usd min-bond-usd]
    :or   {min-bond-usd 250}} d-rng]
  (let [epoch-cap-rate (/ EPOCH_CAP_BPS 10000.0)
        insurance-rate (/ INSURANCE_CUT_BPS 10000.0)

        ;; Generate resolver bonds from lognormal centred on avg-bond
        ;; stddev = avg-bond × 0.5 gives reasonable spread
        ln-mean  (Math/log avg-bond-usd)
        ln-std   0.5
        bonds    (for [_ (range n-resolvers)]
                   (max min-bond-usd
                        (* avg-bond-usd
                           (Math/exp (* ln-std (- (rng/next-double d-rng) 0.5) 2)))))

        ;; Slash each resolver a random fraction up to epoch cap
        slash-events (for [bond bonds]
                       (let [slash-fraction (* epoch-cap-rate (rng/next-double d-rng))
                             slashed        (* bond slash-fraction)
                             ins-cut        (* slashed insurance-rate)
                             ;; User claim capped at max-escrow, resolver bond covers rest
                             user-claim     (min MAX_ESCROW_PER_CASE bond)
                             resolver-gap   (max 0.0 (- user-claim (- bond slashed)))]
                         {:slashed slashed :ins-cut ins-cut :resolver-gap resolver-gap}))

        total-ins-incoming (reduce + (map :ins-cut slash-events))
        total-user-gaps    (reduce + (map :resolver-gap slash-events))
        pool-after         (- (+ pool-seed-usd total-ins-incoming) total-user-gaps)]

    {:pool-before       pool-seed-usd
     :total-ins-cut     total-ins-incoming
     :total-user-gaps   total-user-gaps
     :pool-after        pool-after
     :solvency-ratio    (solvency-ratio (+ pool-seed-usd total-ins-incoming) total-user-gaps)
     :pass?             (>= pool-after 0)}))

;; ---------------------------------------------------------------------------
;; Scenario: run n-trials for a single (n-resolvers, avg-bond, pool-seed) triple
;; ---------------------------------------------------------------------------

(defn run-scenario
  "Run n-trials Monte Carlo for one parameter combination.
   Returns {:params ... :pass-rate float :worst-pool-after float :pass? bool}"
  [n-resolvers avg-bond-usd pool-seed-usd n-trials seed]
  (let [d-rng      (rng/make-rng seed)
        analytical (max-epoch-drawdown n-resolvers avg-bond-usd)
        mc-results (doall
                    (for [_ (range n-trials)]
                      (simulate-epoch-solvency
                       {:n-resolvers  n-resolvers
                        :avg-bond-usd avg-bond-usd
                        :pool-seed-usd pool-seed-usd}
                       d-rng)))
        pass-count  (count (filter :pass? mc-results))
        pass-rate   (double (/ pass-count n-trials))
        worst-pool  (apply min (map :pool-after mc-results))
        analytic-sr (solvency-ratio pool-seed-usd (:net-obligation analytical))]
    {:params              {:n-resolvers  n-resolvers
                           :avg-bond-usd avg-bond-usd
                           :pool-seed-usd pool-seed-usd}
     :analytical-solvency analytic-sr
     :mc-pass-rate        pass-rate
     :worst-pool-after    worst-pool
     :pass?               (and (>= analytic-sr 1.0) (>= pass-rate 0.95))
     :class               (cond
                            (and (>= analytic-sr 1.0) (>= pass-rate 0.99)) "A"
                            (and (>= analytic-sr 1.0) (>= pass-rate 0.95)) "B"
                            :else "C")}))

;; ---------------------------------------------------------------------------
;; Sweep
;; ---------------------------------------------------------------------------

(defn run-sweep
  [{:keys [n-trials base-seed]
    :or   {n-trials 500 base-seed 42}}]
  (let [resolver-counts [5 10 20 50]
        avg-bonds       [250 500 1000 2500]
        pool-seeds      [0 500 1000 5000 10000]]
    (doall
     (for [n     resolver-counts
           bond  avg-bonds
           pool  pool-seeds
           :let  [seed (+ base-seed (* n 7) (* bond 3) pool)]]
       (run-scenario n bond pool n-trials seed)))))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn summarize
  [results]
  (let [total     (count results)
        passing   (count (filter :pass? results))
        class-a   (count (filter #(= "A" (:class %)) results))
        class-b   (count (filter #(= "B" (:class %)) results))
        class-c   (count (filter #(= "C" (:class %)) results))
        ;; Design-envelope: n≤20, bond≥500
        envelope  (filter #(and (<= (get-in % [:params :n-resolvers]) 20)
                                (>= (get-in % [:params :avg-bond-usd]) 500))
                           results)
        env-pass  (count (filter :pass? envelope))
        worst     (apply min-key :analytical-solvency results)
        best      (apply max-key :analytical-solvency results)]
    {:total-scenarios      total
     :passing-scenarios    passing
     :class-a class-a :class-b class-b :class-c class-c
     :design-envelope-total (count envelope)
     :design-envelope-pass  env-pass
     :hypothesis-holds?    (= env-pass (count envelope))
     :worst-solvency-ratio (:analytical-solvency worst)
     :worst-params         (:params worst)
     :best-solvency-ratio  (:analytical-solvency best)
     :best-params          (:params best)}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn run-phase-af
  "BM-04: Slashing epoch solvency sweep.

   Tests whether the insurance pool stays solvent across the full
   (n-resolvers × avg-bond × pool-seed) grid under worst-case epoch slashing.

   Pass threshold: solvency-ratio ≥ 1.0 for all design-envelope configs
   (n-resolvers ≤ 20, avg-bond ≥ $500)."
  ([] (run-phase-af {}))
  ([params]
   (println "\n📊 PHASE AF: SLASHING EPOCH SOLVENCY (BM-04)")
   (println "   Hypothesis: insurance pool solvent after worst-case epoch")
   (println (format "   Constants: epoch-cap=%d bps, insurance-cut=%d bps, max-escrow=$%d"
                    EPOCH_CAP_BPS INSURANCE_CUT_BPS MAX_ESCROW_PER_CASE))
   (println "")

   (let [results  (run-sweep params)
         summary  (summarize results)]

     (println "   Sample results (n-resolvers=10, avg-bond=$500):")
     (doseq [r (filter #(and (= 10 (get-in % [:params :n-resolvers]))
                             (= 500 (get-in % [:params :avg-bond-usd])))
                       results)]
       (println (format "     pool-seed=$%-6d  analytic-SR=%.2fx  mc-pass=%.0f%%  %s"
                        (get-in r [:params :pool-seed-usd])
                        (:analytical-solvency r)
                        (* 100 (:mc-pass-rate r))
                        (case (:class r) "A" "✅ A" "B" "✅ B" "❌ C"))))

     (println "")
     (println "═══════════════════════════════════════════════════")
     (println "📋 PHASE AF SUMMARY")
     (println "═══════════════════════════════════════════════════")
     (println (format "   Total configs:    %d" (:total-scenarios summary)))
     (println (format "   Passing (A+B):    %d  (%.0f%%)"
                      (:passing-scenarios summary)
                      (* 100.0 (/ (:passing-scenarios summary) (:total-scenarios summary)))))
     (println (format "   Class A:  %d   Class B: %d   Class C: %d"
                      (:class-a summary) (:class-b summary) (:class-c summary)))
     (println (format "   Design-envelope (%d configs, n≤20 bond≥$500):"
                      (:design-envelope-total summary)))
     (println (format "     Passing: %d / %d"
                      (:design-envelope-pass summary)
                      (:design-envelope-total summary)))
     (println (format "   Worst solvency ratio: %.2fx  params: %s"
                      (:worst-solvency-ratio summary)
                      (:worst-params summary)))
     (println (format "   Hypothesis holds? %s"
                      (if (:hypothesis-holds? summary)
                        "✅ YES — pool solvent in design envelope"
                        "❌ NO  — solvency gap found; increase pool-seed or tighten caps")))
     (println "")

     (assoc summary :results (vec results)))))
