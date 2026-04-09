(ns resolver-sim.sim.phase-aa
  "Phase AA: Governance as Adversary — Selective Enforcement Gaming.

   Tests whether attackers can achieve >20% win rate by gaming governance
   response patterns, specifically:
   - Governance bandwidth limit: only 3 disputes reviewed per epoch
   - Governance bias: high-value disputes reviewed preferentially
   - Low-value dispute flooding: attacker exploits the invisible window
   - Rule drift: repeated small parameter changes create exploitable patterns

   Hypothesis to falsify:
     'Attackers cannot exceed 20% win rate via governance gaming, even when
      governance capacity is limited and biased toward high-value disputes.'

   Also covers the governance capture gap (rule drift) not tested in Phases M/J."
  (:require [resolver-sim.model.rng :as rng]))

;; ============ Governance Bandwidth Model ============

(def governance-capacity-per-epoch
  "Maximum disputes governance can review/freeze per epoch."
  3)

(defn governance-review-probability
  "Probability that governance reviews a dispute given its value.

   High value  (>$100K): 95%
   Medium value ($10K-$100K): 60%
   Low value   (<$10K): 15%"
  [dispute-value]
  (cond
    (>= dispute-value 100000) 0.95
    (>= dispute-value 10000)  0.60
    :else                     0.15))

(defn disputes-reviewed
  "Given a list of disputes this epoch, return which ones governance reviews.
   Governance prioritises highest-value disputes up to capacity limit."
  [disputes capacity]
  (let [sorted (sort-by :value > disputes)
        candidates (filter #(> (rand) (- 1.0 (governance-review-probability (:value %)))) sorted)]
    (take capacity candidates)))

(defn attacker-win?
  "Attacker wins a dispute if governance did NOT review it.
   Base win rate without governance: 35% (imperfect detection).
   With governance review: 5% (very low)."
  [dispute reviewed-set]
  (let [reviewed? (some #(= (:id %) (:id dispute)) reviewed-set)
        win-prob  (if reviewed? 0.05 0.35)]
    (< (rand) win-prob)))

;; ============ Rule Drift Model ============

(defn apply-rule-drift
  "Governance capture via small repeated parameter changes.

   Each epoch there is a small chance a rule changes by a small amount.
   Over 50 epochs, a 2% per-epoch drift compounds to a significant shift.

   Returns adjusted detection probability after drift."
  [base-detection-prob epoch drift-rate]
  (let [accumulated-drift (* drift-rate epoch)
        ;; Drift reduces detection: each 1% drift = 0.5% drop in detection
        detection-reduction (* accumulated-drift 0.5)
        drifted (- base-detection-prob detection-reduction)]
    (max 0.05 drifted)))  ;; Floor at 5% — never zero

;; ============ Test Scenarios ============

(defn test-high-value-flooding
  "Attacker floods with high-value disputes to exhaust governance bandwidth.
   Governance reviews 3/epoch; if 10 high-value disputes arrive, 7 escape."
  []
  (println "\n📋 TEST 1: High-Value Flooding (exhaust governance capacity)")
  (let [n-epochs 50
        n-attacker-disputes 10   ;; Attacker submits 10 disputes/epoch
        dispute-value 150000     ;; All high value — forces governance attention
        results (repeatedly n-epochs
                  (fn []
                    (let [disputes (mapv (fn [i] {:id i :value dispute-value}) (range n-attacker-disputes))
                          reviewed (disputes-reviewed disputes governance-capacity-per-epoch)
                          wins (count (filter #(attacker-win? % reviewed) disputes))]
                      {:wins wins :total n-attacker-disputes
                       :win-rate (/ (double wins) n-attacker-disputes)})))
        avg-win-rate (/ (reduce + (map :win-rate results)) n-epochs)]
    (println (format "   %d attacker disputes/epoch, all $%.0fK (above governance threshold)"
                     n-attacker-disputes (/ dispute-value 1000.0)))
    (println (format "   Governance reviews %d/epoch → %d escape per epoch"
                     governance-capacity-per-epoch (- n-attacker-disputes governance-capacity-per-epoch)))
    (println (format "   Avg attacker win rate: %.1f%%" (* 100 avg-win-rate)))
    (println (format "   Status: %s" (if (< avg-win-rate 0.20) "✅ PASS (<20%)" "❌ FAIL (>20%)")))
    {:scenario :high-value-flooding :avg-win-rate avg-win-rate
     :class (if (< avg-win-rate 0.20) "A" (if (< avg-win-rate 0.35) "B" "C"))}))

(defn test-low-value-invisible
  "Attacker uses low-value disputes that governance ignores (15% review rate).
   Designed to stay below the radar."
  []
  (println "\n📋 TEST 2: Low-Value Invisible Window (below radar)")
  (let [n-epochs 50
        n-attacker-disputes 8
        dispute-value 5000  ;; Low value — 15% review probability
        results (repeatedly n-epochs
                  (fn []
                    (let [disputes (mapv (fn [i] {:id i :value dispute-value}) (range n-attacker-disputes))
                          reviewed (disputes-reviewed disputes governance-capacity-per-epoch)
                          wins (count (filter #(attacker-win? % reviewed) disputes))]
                      {:wins wins :total n-attacker-disputes
                       :win-rate (/ (double wins) n-attacker-disputes)})))
        avg-win-rate (/ (reduce + (map :win-rate results)) n-epochs)]
    (println (format "   %d attacker disputes/epoch, all $%.0fK (below governance radar)"
                     n-attacker-disputes (/ dispute-value 1000.0)))
    (println (format "   Governance review probability: %.0f%%" (* 100 (governance-review-probability dispute-value))))
    (println (format "   Avg attacker win rate: %.1f%%" (* 100 avg-win-rate)))
    (println (format "   Status: %s" (if (< avg-win-rate 0.20) "✅ PASS" "❌ FAIL")))
    {:scenario :low-value-invisible :avg-win-rate avg-win-rate
     :class (if (< avg-win-rate 0.20) "A" (if (< avg-win-rate 0.35) "B" "C"))}))

(defn test-mixed-strategy
  "Attacker mixes high-value distractors with low-value real attacks."
  []
  (println "\n📋 TEST 3: Mixed Strategy (high-value decoys + low-value real attacks)")
  (let [n-epochs 50
        n-decoys 5       ;; High-value decoys to exhaust governance
        n-real 5         ;; Low-value real attacks
        results (repeatedly n-epochs
                  (fn []
                    (let [decoys (mapv (fn [i] {:id (str "d" i) :value 200000}) (range n-decoys))
                          real   (mapv (fn [i] {:id (str "r" i) :value 8000})   (range n-real))
                          all    (concat decoys real)
                          reviewed (disputes-reviewed all governance-capacity-per-epoch)
                          real-wins (count (filter #(attacker-win? % reviewed) real))]
                      {:wins real-wins :total n-real
                       :win-rate (/ (double real-wins) n-real)})))
        avg-win-rate (/ (reduce + (map :win-rate results)) n-epochs)]
    (println (format "   %d high-value decoys + %d low-value real attacks per epoch" n-decoys n-real))
    (println (format "   Decoys capture governance capacity; real attacks escape"))
    (println (format "   Avg real-attack win rate: %.1f%%" (* 100 avg-win-rate)))
    (println (format "   Status: %s" (if (< avg-win-rate 0.20) "✅ PASS" "❌ FAIL")))
    {:scenario :mixed-strategy :avg-win-rate avg-win-rate
     :class (if (< avg-win-rate 0.20) "A" (if (< avg-win-rate 0.35) "B" "C"))}))

(defn test-rule-drift
  "Governance capture via rule drift: 2% drift per epoch over 50 epochs.
   Tests whether attacker win rate increases as detection erodes."
  []
  (println "\n📋 TEST 4: Rule Drift (governance capture via small parameter changes)")
  (let [base-detection 0.30
        drift-rate 0.005  ;; 0.5% per epoch
        n-epochs 50
        results (mapv (fn [epoch]
                        (let [drifted-detection (apply-rule-drift base-detection epoch drift-rate)
                              n-disputes 10
                              disputes (mapv (fn [i] {:id i :value 25000}) (range n-disputes))
                              reviewed (disputes-reviewed disputes governance-capacity-per-epoch)
                              ;; Win prob scales inversely with detection
                              win-prob (- 0.35 (* drifted-detection 0.5))
                              wins (count (filter (fn [d]
                                                    (let [reviewed? (some #(= (:id %) (:id d)) reviewed)]
                                                      (< (rand) (if reviewed? 0.05 (max 0.05 win-prob)))))
                                                  disputes))]
                          {:epoch epoch :detection drifted-detection
                           :wins wins :total n-disputes
                           :win-rate (/ (double wins) n-disputes)}))
                      (range 1 (inc n-epochs)))

        early-win-rate  (/ (reduce + (map :win-rate (take 10 results))) 10.0)
        late-win-rate   (/ (reduce + (map :win-rate (drop 40 results))) 10.0)
        drift-dangerous? (>= late-win-rate 0.20)]
    (println (format "   Rule drift: %.1f%% detection reduction per epoch" (* 100 drift-rate)))
    (println (format "   Early win rate (epochs 1-10):  %.1f%%" (* 100 early-win-rate)))
    (println (format "   Late win rate  (epochs 41-50): %.1f%%" (* 100 late-win-rate)))
    (println (format "   Status: %s" (if (not drift-dangerous?) "✅ PASS (drift tolerable)" "❌ FAIL (drift exceeds threshold)")))
    {:scenario :rule-drift :early-win-rate early-win-rate :late-win-rate late-win-rate
     :drift-dangerous? drift-dangerous?
     :class (if (not drift-dangerous?) "A" (if (< late-win-rate 0.30) "B" "C"))}))

(defn test-governance-timing-attack
  "Attacker times attack during low-salience period (governance distracted).
   Simulated as governance capacity dropping to 1 for 5 epochs."
  []
  (println "\n📋 TEST 5: Governance Timing Attack (low-salience window)")
  (let [n-epochs 50
        low-salience-start 20
        low-salience-end   25
        results (mapv (fn [epoch]
                        (let [capacity (if (and (>= epoch low-salience-start)
                                               (<= epoch low-salience-end))
                                         1  ;; Reduced capacity during distraction
                                         governance-capacity-per-epoch)
                              disputes (mapv (fn [i] {:id i :value 15000}) (range 8))
                              reviewed (disputes-reviewed disputes capacity)
                              wins (count (filter #(attacker-win? % reviewed) disputes))]
                          {:epoch epoch :capacity capacity
                           :wins wins :total 8
                           :win-rate (/ (double wins) 8)}))
                      (range 1 (inc n-epochs)))
        window-results (filter #(<= low-salience-start (:epoch %) low-salience-end) results)
        window-win-rate (if (empty? window-results) 0.0
                          (/ (reduce + (map :win-rate window-results)) (count window-results)))
        overall-win-rate (/ (reduce + (map :win-rate results)) n-epochs)]
    (println (format "   Low-salience window: epochs %d–%d (capacity drops to 1)" low-salience-start low-salience-end))
    (println (format "   Win rate during window: %.1f%%  Overall: %.1f%%"
                     (* 100 window-win-rate) (* 100 overall-win-rate)))
    (println (format "   Status: %s" (if (< window-win-rate 0.35) "✅ ACCEPTABLE" "❌ WINDOW TOO LARGE")))
    {:scenario :timing-attack :window-win-rate window-win-rate :overall-win-rate overall-win-rate
     :class (if (< overall-win-rate 0.20) "A" (if (< overall-win-rate 0.30) "B" "C"))}))

;; ============ Full Phase AA Run ============

(defn run-phase-aa-sweep
  "Run all Phase AA governance gaming tests."
  [_params]

  (println "\n📊 PHASE AA: GOVERNANCE AS ADVERSARY TESTING")
  (println "   Hypothesis: Attackers cannot exceed 20% win rate via governance gaming")
  (println "   Also tests: governance capture via rule drift")
  (println "")

  (let [r1 (test-high-value-flooding)
        r2 (test-low-value-invisible)
        r3 (test-mixed-strategy)
        r4 (test-rule-drift)
        r5 (test-governance-timing-attack)

        all-results [r1 r2 r3 r4 r5]
        class-a (count (filter #(= "A" (:class %)) all-results))
        class-b (count (filter #(= "B" (:class %)) all-results))
        class-c (count (filter #(= "C" (:class %)) all-results))

        ;; Pull win rates where available
        win-rates (keep #(or (:avg-win-rate %) (:late-win-rate %)) all-results)
        max-win-rate (if (seq win-rates) (apply max win-rates) 0.0)
        hypothesis-holds? (< max-win-rate 0.20)]

    (println "\n═══════════════════════════════════════════════════")
    (println "📋 PHASE AA SUMMARY")
    (println "═══════════════════════════════════════════════════")
    (println (format "   Robust (A): %d  Acceptable (B): %d  Fragile (C): %d" class-a class-b class-c))
    (println (format "   Max attacker win rate across scenarios: %.1f%%" (* 100 max-win-rate)))
    (println (format "   Hypothesis holds? %s"
                     (if hypothesis-holds?
                       "✅ YES — governance gaming not profitable"
                       "❌ NO — governance capacity increase needed")))
    (println "")
    (if hypothesis-holds?
      (do (println "   Confidence impact: +7% (governance capture not critical risk)")
          (println "   Recommendation: Increase governance capacity to 5/epoch; add low-value floor"))
      (do (println "   Confidence impact: 0% (governance gaming exploitable)")
          (println "   Recommendation: Raise capacity, add minimum review floor for all dispute values")))
    (println "")

    {:results all-results
     :class-a class-a :class-b class-b :class-c class-c
     :max-win-rate max-win-rate
     :hypothesis-holds? hypothesis-holds?}))
