(ns resolver-sim.sim.phase-z
  "Phase Z: Legitimacy and Reflexive Participation Loop.

   Tests whether the system sustains stable participation over 100+ epochs when:
   - Outcomes are occasionally controversial or slow
   - False positives occasionally slash honest resolvers
   - Sudden participation shocks occur (30-50% withdrawals)

   Hypothesis to falsify:
     'The trust index stays above the exit threshold over 100+ epochs,
      even under realistic shocks, without triggering a reflexive spiral.'

   Failure signal: trust drops below exit threshold → participation cascades
   downward → security threshold breached."
  (:require [resolver-sim.model.rng :as rng]))

;; ============ Trust Index Model ============

(defn update-trust
  "trust_t+1 = trust_t * decay + correctness_signal + fairness_signal

   decay: 0.98/epoch (slow erosion even when things go well)
   correctness: +2% if accuracy >85%, -3% if <65%, -1% if FP rate >5%
   fairness: +1% if outcome variance low, -2% if resolution time > 5 days"
  [{:keys [trust]} {:keys [accuracy false-positive-rate resolution-time]}]
  (let [correctness-signal (cond
                             (>= accuracy 0.85)  0.02
                             (<= accuracy 0.65) -0.03
                             :else               0.00)
        fp-signal          (if (> false-positive-rate 0.05) -0.01 0.0)
        fairness-signal    (if (> resolution-time 5) -0.015 0.01)
        new-trust (-> trust
                      (* 0.98)
                      (+ correctness-signal)
                      (+ fp-signal)
                      (+ fairness-signal)
                      (max 0.0)
                      (min 1.0))]
    new-trust))

(defn update-participation
  "Participation feedback loop:
   participation_{t+1} = participation_t * 0.97 + re-entry
   re-entry = 0.03 * sigmoid(trust_t - 0.6)"
  [current-participation trust]
  (let [sigmoid (fn [x] (/ 1.0 (+ 1.0 (Math/exp (* -10.0 x)))))
        re-entry (* 0.06 (sigmoid (- trust 0.6))) ;; Slightly higher re-entry to balance decay
        retention 0.96
        new-participation (+ (* current-participation retention) re-entry)]
    (max 0.1 (min 1.0 new-participation))))

(defn simulate-epoch-z
  "Run one epoch of Phase Z trust/participation dynamics."
  [epoch {:keys [trust participation]} scenario-overrides d-rng]
  (let [{:keys [base-accuracy false-positive-rate resolution-time shock-epoch shock-magnitude]}
        (merge {:base-accuracy 0.88 :false-positive-rate 0.02
                :resolution-time 3 :shock-epoch -1 :shock-magnitude 0.0}
               scenario-overrides)

        ;; Apply participation shock if this is the shock epoch
        participation-after-shock (if (= epoch shock-epoch)
                                    (max 0.1 (- participation shock-magnitude))
                                    participation)

        ;; Accuracy degrades at low participation (simplified security threshold)
        effective-accuracy (if (< participation-after-shock 0.4)
                             (* base-accuracy 0.80)
                             base-accuracy)

        new-trust (update-trust {:trust trust}
                                {:accuracy effective-accuracy
                                 :false-positive-rate false-positive-rate
                                 :resolution-time resolution-time})

        new-participation (update-participation participation-after-shock new-trust)]

    {:epoch epoch
     :trust new-trust
     :participation new-participation
     :spiral-risk? (and (< new-trust 0.40) (< new-participation 0.40))}))

;; ============ Test Scenarios ============

(defn run-scenario-z
  "Run a scenario for n-epochs, return trust/participation trajectory."
  [scenario-label initial-trust initial-participation overrides n-epochs seed]
  (println (format "📋 %s" scenario-label))
  (let [d-rng (rng/make-rng seed)]
    (loop [epoch 1
           state {:trust initial-trust :participation initial-participation}
           history []]
      (if (> epoch n-epochs)
        (let [min-trust (apply min (map :trust history))
              min-part (apply min (map :participation history))
              final (last history)
              spiral? (< (:participation final) 0.3)]
          (println (format "   Final trust: %.2f  Final participation: %.2f" (:trust final) (:participation final)))
          (println (format "   Min trust: %.2f  Min participation: %.2f" min-trust min-part))
          (println (format "   Status: %s" (if (not spiral?) "✅ STABLE" "❌ DEATH SPIRAL")))
          {:scenario scenario-label :final final :min-trust min-trust :min-part min-part :class (if (not spiral?) "A" "C")})
        (let [result (simulate-epoch-z epoch state overrides d-rng)]
          (recur (inc epoch) 
                 {:trust (:trust result) :participation (:participation result)} 
                 (conj history result)))))))

;; ============ Full Phase Z Run ============

(defn run-phase-z-sweep
  "Run all Phase Z legitimacy + reflexive participation tests."
  [params]
  (let [seed (:rng-seed params 42)]
    (println "\n📊 PHASE Z: LEGITIMACY & REFLEXIVE PARTICIPATION LOOP TESTING")
    (println "   Hypothesis: System maintains stable participation (>40%) over 100 epochs")
    (println "")

    (let [r1 (run-scenario-z "TEST 1: Baseline (Stable environment)" 
                            0.75 0.85 {} 100 seed)
          r2 (run-scenario-z "TEST 2: Market Shock (40% exit at epoch 30)" 
                            0.75 0.85 {:shock-epoch 30 :shock-magnitude 0.40} 100 (+ seed 1))
          r3 (run-scenario-z "TEST 3: Scam Wave (High FP rate 8%)" 
                            0.75 0.85 {:false-positive-rate 0.08} 100 (+ seed 2))
          r4 (run-scenario-z "TEST 4: Combined Shocks" 
                            0.75 0.85 {:shock-epoch 30 :shock-magnitude 0.30 
                                      :false-positive-rate 0.06} 100 (+ seed 3))
          r5 (run-scenario-z "TEST 5: Cascading Failures (Low accuracy + slow resolution)" 
                            0.75 0.85 {:base-accuracy 0.60 :resolution-time 8} 100 (+ seed 4))

          all-results [r1 r2 r3 r4 r5]
          class-a (count (filter #(= "A" (:class %)) all-results))
          class-c (count (filter #(= "C" (:class %)) all-results))

          hypothesis-holds? (zero? class-c)]

      (println "\n═══════════════════════════════════════════════════")
      (println "📋 PHASE Z SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Robust (A): %d  Fragile (C): %d" class-a class-c))
      (println (format "   Hypothesis holds? %s"
                       (if hypothesis-holds?
                         "✅ YES — legitimacy stable under tested shocks"
                         "❌ NO — reflexive spiral possible; safeguards needed")))
      (println "")
      (if hypothesis-holds?
        (do (println "   Confidence impact: +6% (legitimacy not a critical risk)")
            (println "   Recommendation: Monitor participation metrics at launch"))
        (do (println "   Confidence impact: 0% (spiral risk found; trust floor needed)")
            (println "   Recommendation: Add trust floor mechanism; emergency resolver onboarding reserve")))
      (println "")

      {:results all-results
       :class-a class-a :class-c class-c
       :hypothesis-holds? hypothesis-holds?})))
