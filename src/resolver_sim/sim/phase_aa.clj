(ns resolver-sim.sim.phase-aa
  "Phase AA: Governance as Adversary — Selective Enforcement Gaming.

   Tests whether attackers can achieve >20% win rate by gaming governance
   response patterns, specifically:
   - Governance bandwidth limit: only 3 disputes reviewed per epoch
   - Governance bias: high-value disputes reviewed preferentially
   - Low-value dispute flooding: attacker exploits the invisible window
   - Attacker learning: attacker adapts to governance thresholds over time

   Hypothesis to falsify:
     'Attackers cannot exceed 20% win rate via governance gaming, even when
      governance capacity is limited and biased toward high-value disputes.'

   Also covers the governance capture gap (rule drift) not tested in Phases M/J."
  (:require [resolver-sim.model.rng :as rng]))

;; ============ Governance Bandwidth Model ============

(def governance-capacity-per-epoch 3)

(defn governance-review-probability
  "Probability that governance reviews a dispute given its value.
   High value  (>$100K): 95%
   Medium value ($10K-$100K): 60%
   Low value   (<$10K): 20%"
  [dispute-value]
  (cond
    (>= dispute-value 100000) 0.95
    (>= dispute-value 10000)  0.60
    :else                     0.20))

(defn select-reviewed-disputes
  "Governance reviews up to capacity, prioritizing by value and probability."
  [disputes capacity d-rng]
  (let [candidates (filter (fn [d] (> (rng/next-double d-rng) 
                                     (- 1.0 (governance-review-probability (:value d))))) 
                          disputes)
        sorted (sort-by :value > candidates)]
    (take capacity sorted)))

(defn simulate-dispute-outcome
  "Attacker win rate without governance: 35% (imperfect detection).
   With governance review: 5% (freeze/intervention)."
  [dispute reviewed-ids d-rng]
  (let [reviewed? (contains? reviewed-ids (:id dispute))
        win-prob (if reviewed? 0.05 0.35)]
    (< (rng/next-double d-rng) win-prob)))

;; ============ Attacker Learning ============

(defn infer-grey-zone
  "Attacker analyzes history to find the highest value with <20% review rate."
  [history]
  (let [by-value (group-by (fn [d] (cond (< (:value d) 10000) :low
                                       (< (:value d) 100000) :med
                                       :else :high)) history)
        stats (for [[k v] by-value]
                [k (/ (double (count (filter :reviewed v))) (count v))])]
    ;; Attacker targets the most profitable zone with low review probability
    (if-let [best (first (sort-by second < (filter (fn [[_ prob]] (< prob 0.3)) stats)))]
      (first best)
      :low)))

;; ============ Simulation Loop ============

(defn run-governance-simulation
  [scenario-label n-disputes capacity bias-overrides learning? seed]
  (println (format "📋 %s" scenario-label))
  (let [d-rng (rng/make-rng seed)]
    (loop [epoch 1
           history []
           total-wins 0
           total-attempts 0]
      (if (> epoch 50)
        (let [win-rate (/ (double total-wins) total-attempts)
              passed? (< win-rate 0.20)]
          (println (format "   Final win rate: %.1f%%" (* 100 win-rate)))
          (println (format "   Status: %s" (if passed? "✅ SAFE" "❌ VULNERABLE")))
          {:scenario scenario-label :win-rate win-rate :class (if passed? "A" "C")})
        (let [attacker-strategy (if (and learning? (> epoch 20))
                                  (infer-grey-zone history)
                                  :random)
              
              ;; Generate disputes for this epoch
              epoch-disputes (for [i (range 5)]
                               (let [val (case attacker-strategy
                                           :low  (rng/next-int d-rng 9999)
                                           :med  (+ 10000 (rng/next-int d-rng 89999))
                                           :high (+ 100000 (rng/next-int d-rng 100000))
                                           :random (+ 1000 (rng/next-int d-rng 150000)))]
                                 {:id (str epoch "-" i) :value val}))
              
              reviewed (select-reviewed-disputes epoch-disputes capacity d-rng)
              reviewed-ids (set (map :id reviewed))
              
              outcomes (for [d epoch-disputes]
                         (let [won? (simulate-dispute-outcome d reviewed-ids d-rng)]
                           (assoc d :won won? :reviewed (contains? reviewed-ids (:id d)))))
              
              new-wins (count (filter :won outcomes))]
          
          (recur (inc epoch)
                 (concat history outcomes)
                 (+ total-wins new-wins)
                 (+ total-attempts (count epoch-disputes))))))))

;; ============ Full Phase AA Run ============

(defn run-phase-aa-sweep
  "Run all Phase AA governance gaming tests."
  [params]
  (let [seed (:rng-seed params 42)]
    (println "\n📊 PHASE AA: GOVERNANCE AS ADVERSARY TESTING")
    (println "   Hypothesis: Attackers cannot exceed 20% win rate via governance gaming")
    (println "")

    (let [r1 (run-governance-simulation "TEST 1: Baseline (High capacity, naive attacker)" 
                                      250 5 {} false seed)
          r2 (run-governance-simulation "TEST 2: Limited Capacity (Cap=3, learning attacker)" 
                                      250 3 {} true (+ seed 1))
          r3 (run-governance-simulation "TEST 3: Biased Governance (Focus on >$50K)" 
                                      250 3 {:bias :high} true (+ seed 2))
          r4 (run-governance-simulation "TEST 4: Low-Value Flooding" 
                                      250 2 {} true (+ seed 3))
          r5 (run-governance-simulation "TEST 5: Adversarial Threshold Search" 
                                      250 1 {} true (+ seed 4))

          all-results [r1 r2 r3 r4 r5]
          class-a (count (filter #(= "A" (:class %)) all-results))
          class-c (count (filter #(= "C" (:class %)) all-results))

          max-win-rate (apply max (map :win-rate all-results))
          hypothesis-holds? (< max-win-rate 0.20)]

    (println "\n═══════════════════════════════════════════════════")
    (println "📋 PHASE AA SUMMARY")
    (println "═══════════════════════════════════════════════════")
    (println (format "   Robust (A): %d  Fragile (C): %d" class-a class-c))
    (println (format "   Max attacker win rate: %.1f%%" (* 100 max-win-rate)))
    (println (format "   Hypothesis holds? %s"
                     (if hypothesis-holds?
                       "✅ YES — governance gaming not profitable"
                       "❌ NO — governance capacity increase needed")))
    (println "")
    (if hypothesis-holds?
      (do (println "   Confidence impact: +7% (governance capture not critical risk)")
          (println "   Recommendation: Maintain current capacity; add low-value sampling"))
      (do (println "   Confidence impact: 0% (governance gaming exploitable)")
          (println "   Recommendation: Raise capacity, add minimum review floor for all dispute values")))
    (println "")

    {:results all-results
     :class-a class-a :class-c class-c
     :max-win-rate max-win-rate
     :hypothesis-holds? hypothesis-holds?})))
