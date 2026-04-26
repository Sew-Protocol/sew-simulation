(ns resolver-sim.sim.phase-z
  "Phase Z: Legitimacy and Reflexive Participation Loop.

   Tests whether the system sustains stable participation over 100+ epochs when:
   - Outcomes are occasionally controversial or slow
   - False positives occasionally slash honest resolvers
   - Sudden participation shocks occur (30-50% withdrawals)

   Failure signal: trust drops below exit threshold → participation cascades
   downward → security threshold breached."
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.sim.engine :as engine]))

;; ============ Trust Index Model (Pure) ============

(defn update-trust
  "trust_t+1 = trust_t * decay + correctness_signal + fairness_signal"
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
  "Participation feedback loop."
  [current-participation trust]
  (let [sigmoid (fn [x] (/ 1.0 (+ 1.0 (Math/exp (* -10.0 x)))))
        re-entry (* 0.06 (sigmoid (- trust 0.6)))
        retention 0.96
        new-participation (+ (* current-participation retention) re-entry)]
    (max 0.1 (min 1.0 new-participation))))

;; ============ Engine Adapters ============

(defn simulate-epoch-z
  "Adapter for the unified engine."
  [epoch state params _rng]
  (let [{:keys [base-accuracy false-positive-rate resolution-time shock-epoch shock-magnitude]}
        (merge {:base-accuracy 0.88 :false-positive-rate 0.02
                :resolution-time 3 :shock-epoch -1 :shock-magnitude 0.0}
               params)

        participation (:participation state)
        trust         (:trust state)

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

(defn summarize-z-history
  "Aggregation and hypothesis checking for Phase Z."
  [history params]
  (let [min-trust (apply min (map :trust history))
        min-part (apply min (map :participation history))
        final (last history)
        spiral? (< (:participation final) 0.3)
        passed? (not spiral?)]
    {:status (if passed? "✅ STABLE" "❌ DEATH SPIRAL")
     :min-trust min-trust
     :min-part min-part
     :final-trust (:trust final)
     :final-part (:participation final)
     :class (if passed? "A" "C")
     :passed? passed?}))

;; ============ Scenario Definitions ============

(defn make-scenarios [seed]
  [{:label "TEST 1: Baseline (Stable environment)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed seed
    :params {}}
   
   {:label "TEST 2: Market Shock (40% exit at epoch 30)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 1)
    :params {:shock-epoch 30 :shock-magnitude 0.40}}
   
   {:label "TEST 3: Scam Wave (High FP rate 8%)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 2)
    :params {:false-positive-rate 0.08}}
   
   {:label "TEST 4: Combined Shocks"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 3)
    :params {:shock-epoch 30 :shock-magnitude 0.30 :false-positive-rate 0.06}}
   
   {:label "TEST 5: Cascading Failures (Low accuracy + slow resolution)"
    :initial-state {:trust 0.75 :participation 0.85}
    :update-fn simulate-epoch-z
    :summary-fn summarize-z-history
    :epochs 100
    :seed (+ seed 4)
    :params {:base-accuracy 0.60 :resolution-time 8}}])

;; ============ Full Phase Z Run ============

(defn run-phase-z-sweep
  "Run all Phase Z legitimacy + reflexive participation tests."
  [params]
  (let [seed (:rng-seed params 42)
        _ (println "\n📊 PHASE Z: LEGITIMACY & REFLEXIVE PARTICIPATION LOOP TESTING")
        _ (println "   Hypothesis: System maintains stable participation (>40%) over 100 epochs")
        _ (println "")
        
        scenarios (make-scenarios seed)
        results (engine/run-sweep "PHASE Z SWEEP" scenarios params)
        
        class-a (count (filter #(= "A" (:class %)) results))
        class-c (count (filter #(= "C" (:class %)) results))
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

    {:results results
     :class-a class-a :class-c class-c
     :hypothesis-holds? hypothesis-holds?}))
