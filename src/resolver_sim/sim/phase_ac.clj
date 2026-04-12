(ns resolver-sim.sim.phase-ac
  "Phase AC: Trust Floor & Emergency Onboarding.

   Safeguard for the Phase Z vulnerability: legitimacy collapse caused by the
   reflexive participation spiral (experienced resolvers exit → quality drops →
   user confidence drops → fewer disputes → fewer fees → more exits).

   This phase tests whether enforcing a minimum trust floor (≥ min-resolvers
   experienced resolvers must remain active) combined with emergency onboarding
   (new resolvers are fast-tracked when below floor) prevents legitimacy from
   collapsing below the critical threshold of 0.50.

   Model:
     - Trust score: weighted average of resolver experience levels (0–1)
     - Legitimacy: function of trust score and dispute backlog ratio
     - Exit probability: resolver exits when legitimacy × expected-income < threshold
     - Emergency onboarding: when active resolvers < min-resolvers, N new resolvers
       are added each epoch with base trust = onboarding-trust-level

   Hypothesis to confirm:
     With trust floor active, legitimacy never drops below 0.50 across 50 epochs
     even under adverse initial conditions (high-exit-pressure scenario)."
  (:require [resolver-sim.model.rng :as rng]))

;; ---------------------------------------------------------------------------
;; Trust and legitimacy model
;; ---------------------------------------------------------------------------

(defn trust-score
  "Weighted average experience of active resolvers.
   experience = 0.0 (new) → 1.0 (fully seasoned)"
  [resolvers]
  (if (empty? resolvers)
    0.0
    (/ (reduce + (map :experience resolvers)) (count resolvers))))

(defn compute-pressure
  "Instantaneous backlog pressure = disputes / (capacity × resolvers), capped at 1.0."
  [disputes-per-epoch resolver-capacity n-resolvers]
  (min 1.0 (/ disputes-per-epoch (max 1.0 (* resolver-capacity (double n-resolvers))))))

(defn rolling-avg-pressure
  "Average of the last rolling-window pressure samples.
   Uses all available history when fewer than rolling-window samples have been recorded."
  [pressure-history current-pressure rolling-window]
  (let [window (take-last rolling-window (conj pressure-history current-pressure))]
    (/ (reduce + window) (count window))))

(defn legitimacy
  "Legitimacy = trust-score × (1 − effective-pressure).
   effective-pressure is the rolling-window average of backlog pressure,
   smoothing transient spikes to prevent premature collapse detection."
  [resolvers effective-pressure]
  (* (trust-score resolvers) (- 1.0 effective-pressure)))

(defn exit-probability
  "Probability a resolver exits this epoch.
   High legitimacy and high fee-income reduce exit probability.
   Baseline exit noise = 5% per epoch."
  [leg fee-per-dispute]
  (let [income-factor (min 1.0 (/ fee-per-dispute 100.0))   ; normalised to 100
        retention     (* leg income-factor)
        raw           (- 0.90 (* retention 0.85))]          ; range [0.05, 0.90]
    (max 0.05 (min 0.90 raw))))

;; ---------------------------------------------------------------------------
;; Single epoch step
;; ---------------------------------------------------------------------------

(defn step-epoch
  "Advance the resolver pool by one epoch.

   Uses a rolling-window average of backlog pressure (rather than a single-epoch
   snapshot) to compute legitimacy, smoothing transient spikes.

   Returns updated resolver list with exits removed, experiences incremented,
   and new emergency onboarding entrants added if below floor.
   Also returns :pressure-history (updated window) for threading."
  [resolvers disputes-per-epoch resolver-capacity fee-per-dispute
   min-resolvers onboarding-trust emergency-per-epoch d-rng floor-active?
   pressure-history rolling-window]
  (let [instant-p    (compute-pressure disputes-per-epoch resolver-capacity (count resolvers))
        eff-p        (rolling-avg-pressure pressure-history instant-p rolling-window)
        leg          (legitimacy resolvers eff-p)
        exit-p       (exit-probability leg fee-per-dispute)
        ;; Apply exits
        surviving    (filter (fn [r] (>= (rng/next-double d-rng) exit-p)) resolvers)
        ;; Increment experience (capped at 1.0)
        matured      (mapv (fn [r] (update r :experience #(min 1.0 (+ % 0.05)))) surviving)
        ;; Emergency onboarding when below floor (only if floor is active)
        below-floor? (and floor-active? (< (count matured) min-resolvers))
        n-to-onboard (if below-floor? emergency-per-epoch 0)
        new-batch    (repeatedly n-to-onboard #(hash-map :experience onboarding-trust))
        final        (into matured new-batch)
        new-history  (vec (take-last rolling-window (conj pressure-history instant-p)))]
    {:resolvers        final
     :legitimacy       leg
     :instant-pressure instant-p
     :eff-pressure     eff-p
     :exit-count       (- (count resolvers) (count surviving))
     :onboarded        n-to-onboard
     :below-floor      below-floor?
     :pressure-history new-history}))

;; ---------------------------------------------------------------------------
;; Single scenario simulation
;; ---------------------------------------------------------------------------

(defn run-trust-floor-scenario
  "Simulate n-epochs with the given trust floor configuration.

   Uses a 5-epoch rolling average for backlog pressure (see step-epoch).
   Returns {:epoch-results [...] :min-legitimacy f :legitimacy-collapsed? bool}."
  [n-epochs n-resolvers-initial disputes-per-epoch resolver-capacity fee-per-dispute
   min-resolvers onboarding-trust emergency-per-epoch floor-active? seed]
  (let [d-rng             (rng/make-rng seed)
        rolling-window    5
        initial-resolvers (vec (repeatedly n-resolvers-initial
                                           #(hash-map :experience (+ 0.3 (* 0.4 (rng/next-double d-rng))))))
        epoch-results
        (loop [resolvers        initial-resolvers
               epoch            1
               results          []
               pressure-history []]
          (if (> epoch n-epochs)
            results
            (let [step (step-epoch resolvers disputes-per-epoch resolver-capacity
                                   fee-per-dispute min-resolvers onboarding-trust
                                   emergency-per-epoch d-rng floor-active?
                                   pressure-history rolling-window)]
              (recur (:resolvers step)
                     (inc epoch)
                     (conj results (assoc step :epoch epoch
                                              :resolver-count (count (:resolvers step))))
                     (:pressure-history step)))))]
    {:epoch-results        epoch-results
     :min-legitimacy       (apply min (map :legitimacy epoch-results))
     :final-resolver-count (:resolver-count (last epoch-results))
     :legitimacy-collapsed? (some #(< (:legitimacy %) 0.50) epoch-results)}))

;; ---------------------------------------------------------------------------
;; Full Phase AC sweep
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Threshold search: find minimum viable (emergency-per-epoch, onboarding-trust,
;; fee-per-dispute) that prevents legitimacy collapse.
;;
;; The exit-probability function is parameterised by legitimacy and fee income:
;;   exit-p = 0.90 - 0.85 × (legitimacy × min(1, fee/100))
;; This bottoms out at 0.05 (irreducible noise) only when leg = 1 and fee ≥ 100.
;; Below fee = 100, income-factor < 1, capping retention and raising exit-p.
;;
;; The search sweeps three levers:
;;   1. fee-per-dispute ∈ {50, 100, 150}       — income normalisation break-even
;;   2. emergency-per-epoch ∈ {2, 4, 6, 8}    — onboarding throughput
;;   3. onboarding-trust ∈ {0.2, 0.5, 0.8}    — quality of emergency batch
;;
;; For each cell it reports min-legitimacy and whether collapse occurred.
;; ---------------------------------------------------------------------------

(defn run-phase-ac-threshold-sweep
  "3-axis threshold search for minimum viable trust-floor configuration.
   Sweeps fee-per-dispute × emergency-per-epoch × onboarding-trust.
   Returns a grid result map and the minimum viable configuration found."
  [params]
  (let [seed               (get params :rng-seed 42)
        n-epochs           (get params :n-epochs 50)
        n-resolvers        (get params :n-resolvers 10)
        disputes-per-epoch (get params :disputes-per-epoch 100)
        resolver-capacity  (get params :resolver-capacity 15)
        min-resolvers      (get params :min-resolvers 7)   ; ceil(100/15) = 7
        fees               [50 100 150]
        emergencies        [2 4 6 8]
        trusts             [0.2 0.5 0.8]]

    (println "\n📊 PHASE AC THRESHOLD SEARCH")
    (println "   Sweeping: fee-per-dispute × emergency-per-epoch × onboarding-trust")
    (println (format "   Dispute load: %d/epoch, resolver capacity: %d/resolver"
                     disputes-per-epoch resolver-capacity))
    (println (format "   Min viable pool: %d resolvers (ceil(%d/%d))"
                     (int (Math/ceil (/ disputes-per-epoch resolver-capacity)))
                     disputes-per-epoch resolver-capacity))
    (println "")

    (let [grid-results
          (for [fee fees
                emer emergencies
                trust trusts
                :let [r (run-trust-floor-scenario
                         n-epochs n-resolvers disputes-per-epoch resolver-capacity
                         fee min-resolvers trust emer true seed)]]
            {:fee         fee
             :emergency   emer
             :trust       trust
             :min-leg     (:min-legitimacy r)
             :final-pool  (:final-resolver-count r)
             :collapsed?  (:legitimacy-collapsed? r)
             :passes?     (not (:legitimacy-collapsed? r))})

          viable (filter :passes? grid-results)

          ;; Minimum viable: smallest (fee, emergency, trust) that passes
          ;; Prioritise lowest emergency (cheapest safeguard), then lowest fee
          min-viable (->> viable
                          (sort-by (juxt :emergency :fee :trust))
                          first)]

      ;; Print summary table
      (println "   fee/disp  emer/epoch  trust  min-leg  pool  result")
      (println "   ────────  ──────────  ─────  ───────  ────  ──────")
      (doseq [{:keys [fee emergency trust min-leg final-pool passes?]} grid-results]
        (println (format "   %-9d %-11d %-6.1f %-8.3f %-5d %s"
                         fee emergency trust min-leg final-pool
                         (if passes? "✅ STABLE" "❌ SPIRAL"))))
      (println "")

      (println "═══════════════════════════════════════════════════")
      (println "📋 PHASE AC THRESHOLD SEARCH SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Viable configurations found: %d / %d"
                       (count viable) (count grid-results)))
      (if min-viable
        (do
          (println (format "   Minimum viable config:"))
          (println (format "     fee-per-dispute    = %d" (:fee min-viable)))
          (println (format "     emergency-per-epoch = %d" (:emergency min-viable)))
          (println (format "     onboarding-trust   = %.1f" (:trust min-viable)))
          (println (format "     Min legitimacy     = %.3f" (:min-leg min-viable)))
          (println "")
          (println "   Recommendation: Implement trust floor with above minimum config")
          (println "   Confidence impact: +5% (Phase Z vulnerability mitigated)"))
        (do
          (println "   ❌ NO viable configuration found in search space")
          (println "   Structural constraint: exit-probability floor too high for")
          (println (format "     this dispute load (%d/epoch) — reduce load or redesign" disputes-per-epoch))
          (println "     exit-probability formula before deploying trust floor")))
      (println "")

      {:grid-results   (vec grid-results)
       :viable-count   (count viable)
       :total-count    (count grid-results)
       :min-viable     min-viable
       :hypothesis-holds? (some? min-viable)})))

(defn run-phase-ac-sweep
  "Test trust floor configurations against legitimacy collapse scenarios."
  [params]
  (let [seed               (get params :rng-seed 42)
        n-epochs           (get params :n-epochs 50)
        n-resolvers        (get params :n-resolvers 10)
        disputes-per-epoch (get params :disputes-per-epoch 100)
        resolver-capacity  (get params :resolver-capacity 15)
        fee-per-dispute    (get params :fee-per-dispute 50)
        min-resolvers      (get params :min-resolvers 3)
        onboarding-trust   (get params :onboarding-trust 0.20)
        emergency-per-ep   (get params :emergency-per-epoch 2)]

    (println "\n📊 PHASE AC: TRUST FLOOR & EMERGENCY ONBOARDING")
    (println "   Hypothesis: trust floor keeps legitimacy ≥ 0.50 across 50 epochs")
    (println "")

    (let [scenarios
          [{:label "No floor (baseline)"           :floor-active? false}
           {:label "Trust floor active"            :floor-active? true}
           {:label "Floor + aggressive onboarding" :floor-active? true
            :emergency-override (* 2 emergency-per-ep)}]

          results
          (mapv (fn [{:keys [label floor-active? emergency-override]}]
                  (println (format "   Testing: %s" label))
                  (let [emer (or emergency-override emergency-per-ep)
                        r    (run-trust-floor-scenario
                              n-epochs n-resolvers disputes-per-epoch resolver-capacity
                              fee-per-dispute min-resolvers onboarding-trust emer
                              floor-active? seed)]
                    (println (format "     Min legitimacy: %.3f  Collapsed? %s"
                                     (:min-legitimacy r)
                                     (if (:legitimacy-collapsed? r) "❌ YES" "✅ NO")))
                    (println (format "     Final resolvers: %d" (:final-resolver-count r)))
                    {:label              label
                     :floor-active?      floor-active?
                     :min-legitimacy     (:min-legitimacy r)
                     :final-resolvers    (:final-resolver-count r)
                     :collapsed?         (:legitimacy-collapsed? r)
                     :passes?            (not (:legitimacy-collapsed? r))}))
                scenarios)

          class-a           (count (filter :passes? results))
          class-c           (count (remove :passes? results))
          hypothesis-holds? (some #(and (:floor-active? %) (:passes? %)) results)]

      (println "\n═══════════════════════════════════════════════════")
      (println "📋 PHASE AC SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Safe (A): %d  Collapsed (C): %d" class-a class-c))
      (println (format "   Hypothesis holds? %s"
                       (if hypothesis-holds?
                         "✅ YES — trust floor prevents legitimacy collapse"
                         "❌ NO — trust floor insufficient; spiral still occurs")))
      (println "")
      (if hypothesis-holds?
        (do (println "   Recommendation: Implement trust floor with min-resolvers ≥ 3")
            (println "   Confidence impact: +5% (Phase Z vulnerability mitigated)"))
        (do (println "   Recommendation: Raise emergency onboarding rate or onboarding trust")
            (println "   Confidence impact: 0%")))
      (println "")

      {:results           results
       :class-a           class-a
       :class-c           class-c
       :hypothesis-holds? hypothesis-holds?})))
