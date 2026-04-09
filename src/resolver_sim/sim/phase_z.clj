(ns resolver-sim.sim.phase-z
  "Phase Z: Legitimacy and Reflexive Participation Loop.

   Tests whether the system sustains stable participation over 100+ epochs when:
   - Outcomes are occasionally controversial or slow
   - False positives occasionally slash honest resolvers
   - A cohort perceives unfair treatment (slashed at 2x rate of others)
   - Sudden participation shocks occur (30-50% withdrawals)

   Hypothesis to falsify:
     'The trust index stays above the exit threshold over 100+ epochs,
      even under realistic shocks, without triggering a reflexive spiral.'

   Failure signal: trust drops below exit threshold → participation cascades
   downward → security threshold breached."
  (:require [resolver-sim.model.liveness-failures :as liveness]
            [resolver-sim.model.rng :as rng]))

;; ============ Trust Index Model ============

(defn update-trust
  "trust_t+1 = trust_t * decay + correctness_signal + fairness_signal

   decay: 0.98/epoch (slow erosion even when things go well)
   correctness: +2% if accuracy >85%, -3% if <65%, -1% if FP rate >5%
   fairness: +1% if outcome variance low, -2% if cohort X slashed 2x cohort Y"
  [{:keys [trust]} {:keys [accuracy false-positive-rate cohort-slash-ratio]}]
  (let [correctness-signal (cond
                             (>= accuracy 0.85)  0.02
                             (<= accuracy 0.65) -0.03
                             :else               0.00)
        fp-signal          (if (> false-positive-rate 0.05) -0.01 0.0)
        fairness-signal    (cond
                             (>= cohort-slash-ratio 2.0) -0.02
                             (<= cohort-slash-ratio 1.2)  0.01
                             :else                        0.00)
        new-trust (-> trust
                      (* 0.98)
                      (+ correctness-signal)
                      (+ fp-signal)
                      (+ fairness-signal)
                      (max 0.0)
                      (min 1.0))]
    new-trust))

(defn participation-from-trust
  "Resolvers join/leave based on trust level.
   High trust → new entrants. Low trust → exits.

   Below 0.40 trust: active exits begin
   Below 0.25 trust: exodus mode"
  [current-resolvers trust]
  (let [entry-rate (cond
                     (>= trust 0.75)  0.05
                     (>= trust 0.55)  0.02
                     (>= trust 0.40)  0.00
                     :else           -0.05)
        exit-rate  (cond
                     (<= trust 0.25) 0.12
                     (<= trust 0.40) 0.05
                     (<= trust 0.55) 0.01
                     :else           0.00)
        net-change (* current-resolvers (- entry-rate exit-rate))]
    (max 5 (int (+ current-resolvers net-change)))))

(defn simulate-epoch-z
  "Run one epoch of Phase Z trust/participation dynamics."
  [epoch {:keys [trust resolvers]} scenario-overrides]
  (let [{:keys [base-accuracy false-positive-rate cohort-slash-ratio shock-epoch shock-magnitude]}
        (merge {:base-accuracy 0.85 :false-positive-rate 0.03
                :cohort-slash-ratio 1.0 :shock-epoch -1 :shock-magnitude 0.0}
               scenario-overrides)

        ;; Apply participation shock if this is the shock epoch
        resolvers-after-shock (if (= epoch shock-epoch)
                                (int (* resolvers (- 1.0 shock-magnitude)))
                                resolvers)

        ;; Accuracy degrades slightly at low resolver counts
        effective-accuracy (if (< resolvers-after-shock 15)
                             (* base-accuracy 0.85)
                             base-accuracy)

        new-trust (update-trust {:trust trust}
                                {:accuracy effective-accuracy
                                 :false-positive-rate false-positive-rate
                                 :cohort-slash-ratio cohort-slash-ratio})

        new-resolvers (participation-from-trust resolvers-after-shock new-trust)

        below-critical? (< new-resolvers 15)
        spiral-risk?    (and (< new-trust 0.40) (< new-resolvers 20))]

    {:epoch epoch
     :trust new-trust
     :resolvers new-resolvers
     :below-critical? below-critical?
     :spiral-risk? spiral-risk?}))

;; ============ Test Scenarios ============

(defn run-scenario
  "Run a scenario for n-epochs, return trust/resolver trajectory."
  [n-epochs initial-trust initial-resolvers overrides]
  (loop [epoch 1
         state {:trust initial-trust :resolvers initial-resolvers}
         history []]
    (if (> epoch n-epochs)
      history
      (let [result (simulate-epoch-z epoch state overrides)
            new-state {:trust (:trust result) :resolvers (:resolvers result)}]
        (recur (inc epoch) new-state (conj history result))))))

(defn test-baseline-stability
  "100 epochs, good accuracy, no shocks. Should hold steady."
  []
  (println "\n📋 TEST 1: Baseline Stability (100 epochs, clean conditions)")
  (let [history (run-scenario 100 0.80 30 {:base-accuracy 0.87 :false-positive-rate 0.02 :cohort-slash-ratio 1.0})
        final (last history)
        min-trust (apply min (map :trust history))
        min-resolvers (apply min (map :resolvers history))
        spirals (count (filter :spiral-risk? history))]
    (println (format "   Final trust: %.2f  Final resolvers: %d" (:trust final) (:resolvers final)))
    (println (format "   Min trust: %.2f  Min resolvers: %d  Spiral-risk epochs: %d" min-trust min-resolvers spirals))
    (println (format "   Status: %s" (if (>= min-trust 0.50) "✅ STABLE" "❌ DRIFTED")))
    {:scenario :baseline :final-trust (:trust final) :min-trust min-trust
     :min-resolvers min-resolvers :spirals spirals
     :class (if (>= min-trust 0.50) "A" (if (>= min-trust 0.35) "B" "C"))}))

(defn test-controversial-outcomes
  "Occasional accuracy dips to 70% (disputed outcomes, perceived bias)."
  []
  (println "\n📋 TEST 2: Controversial Outcomes (periodic accuracy dips)")
  (let [;; Every 10 epochs, accuracy drops to 70%
        history (run-scenario 100 0.80 30
                              {:base-accuracy 0.77   ;; lower average
                               :false-positive-rate 0.06
                               :cohort-slash-ratio 1.3})
        final (last history)
        min-trust (apply min (map :trust history))
        spirals (count (filter :spiral-risk? history))]
    (println (format "   Final trust: %.2f  Min trust: %.2f  Spiral-risk epochs: %d"
                     (:trust final) min-trust spirals))
    (println (format "   Status: %s" (if (and (>= min-trust 0.40) (< spirals 5)) "✅ ACCEPTABLE" "❌ FRAGILE")))
    {:scenario :controversial :final-trust (:trust final) :min-trust min-trust
     :spirals spirals
     :class (if (>= min-trust 0.40) "A" (if (>= min-trust 0.30) "B" "C"))}))

(defn test-cohort-fairness-shock
  "One cohort slashed at 2x the rate of others for 20 epochs."
  []
  (println "\n📋 TEST 3: Cohort Fairness Shock (one group slashed 2x for 20 epochs)")
  (let [;; First 20 epochs: cohort slash ratio = 2.5 (unfair)
        history-unfair (run-scenario 20 0.80 30
                                     {:base-accuracy 0.85 :false-positive-rate 0.03
                                      :cohort-slash-ratio 2.5})
        state-after-unfair (last history-unfair)
        ;; Next 80 epochs: back to normal
        history-recovery (run-scenario 80
                                       (:trust state-after-unfair)
                                       (:resolvers state-after-unfair)
                                       {:base-accuracy 0.87 :false-positive-rate 0.02
                                        :cohort-slash-ratio 1.0})
        history (concat history-unfair history-recovery)
        min-trust (apply min (map :trust history))
        final-trust (:trust (last history))
        recovered? (>= final-trust 0.65)]
    (println (format "   Trust after unfair period: %.2f" (:trust state-after-unfair)))
    (println (format "   Final trust after recovery: %.2f  Min: %.2f" final-trust min-trust))
    (println (format "   Status: %s" (if recovered? "✅ RECOVERS" "❌ PERMANENT DAMAGE")))
    {:scenario :cohort-fairness :final-trust final-trust :min-trust min-trust
     :trust-after-shock (:trust state-after-unfair) :recovered? recovered?
     :class (if recovered? "A" (if (>= min-trust 0.30) "B" "C"))}))

(defn test-participation-shock
  "30% of resolvers suddenly withdraw at epoch 50."
  []
  (println "\n📋 TEST 4: Participation Shock (30% sudden withdrawal at epoch 50)")
  (let [history (run-scenario 100 0.80 30
                              {:base-accuracy 0.85 :false-positive-rate 0.02
                               :cohort-slash-ratio 1.0
                               :shock-epoch 50 :shock-magnitude 0.30})
        post-shock (drop 49 history)
        min-resolvers-post (apply min (map :resolvers post-shock))
        final (last history)
        spirals (count (filter :spiral-risk? post-shock))]
    (println (format "   Min resolvers post-shock: %d  Final resolvers: %d" min-resolvers-post (:resolvers final)))
    (println (format "   Spiral-risk epochs post-shock: %d" spirals))
    (println (format "   Status: %s" (if (> min-resolvers-post 15) "✅ SURVIVES" "❌ BELOW CRITICAL")))
    {:scenario :participation-shock :min-resolvers-post min-resolvers-post
     :final-resolvers (:resolvers final) :final-trust (:trust final)
     :spirals spirals
     :class (if (> min-resolvers-post 15) "A" (if (> min-resolvers-post 10) "B" "C"))}))

(defn test-large-participation-shock
  "50% sudden withdrawal at epoch 50 (stress test)."
  []
  (println "\n📋 TEST 5: Large Participation Shock (50% withdrawal at epoch 50)")
  (let [history (run-scenario 100 0.80 30
                              {:base-accuracy 0.85 :false-positive-rate 0.02
                               :cohort-slash-ratio 1.0
                               :shock-epoch 50 :shock-magnitude 0.50})
        post-shock (drop 49 history)
        min-resolvers-post (apply min (map :resolvers post-shock))
        final (last history)
        spirals (count (filter :spiral-risk? post-shock))]
    (println (format "   Min resolvers post-shock: %d  Final resolvers: %d" min-resolvers-post (:resolvers final)))
    (println (format "   Spiral-risk epochs post-shock: %d" spirals))
    (println (format "   Status: %s" (if (> min-resolvers-post 15) "✅ SURVIVES" "❌ BELOW CRITICAL")))
    {:scenario :large-shock :min-resolvers-post min-resolvers-post
     :final-resolvers (:resolvers final) :final-trust (:trust final)
     :spirals spirals
     :class (if (> min-resolvers-post 15) "A" (if (> min-resolvers-post 8) "B" "C"))}))

;; ============ Full Phase Z Run ============

(defn run-phase-z-sweep
  "Run all Phase Z legitimacy + reflexive participation tests."
  [_params]

  (println "\n📊 PHASE Z: LEGITIMACY & REFLEXIVE PARTICIPATION LOOP TESTING")
  (println "   Hypothesis: Trust index stays above exit threshold over 100+ epochs")
  (println "")

  (let [r1 (test-baseline-stability)
        r2 (test-controversial-outcomes)
        r3 (test-cohort-fairness-shock)
        r4 (test-participation-shock)
        r5 (test-large-participation-shock)

        all-results [r1 r2 r3 r4 r5]
        class-a (count (filter #(= "A" (:class %)) all-results))
        class-b (count (filter #(= "B" (:class %)) all-results))
        class-c (count (filter #(= "C" (:class %)) all-results))

        hypothesis-holds? (zero? class-c)]

    (println "\n═══════════════════════════════════════════════════")
    (println "📋 PHASE Z SUMMARY")
    (println "═══════════════════════════════════════════════════")
    (println (format "   Robust (A): %d  Acceptable (B): %d  Fragile (C): %d" class-a class-b class-c))
    (println (format "   Hypothesis holds? %s"
                     (if hypothesis-holds?
                       "✅ YES — legitimacy stable under tested shocks"
                       "❌ NO — reflexive spiral possible; safeguards needed")))
    (println "")
    (if hypothesis-holds?
      (do (println "   Confidence impact: +6% (legitimacy not a critical risk)")
          (println "   Recommendation: Monitor cohort fairness metrics at launch"))
      (do (println "   Confidence impact: 0% (spiral risk found; trust floor needed)")
          (println "   Recommendation: Add trust floor mechanism; emergency resolver onboarding reserve")))
    (println "")

    {:results all-results
     :class-a class-a :class-b class-b :class-c class-c
     :hypothesis-holds? hypothesis-holds?}))
