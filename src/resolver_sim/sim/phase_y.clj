(ns resolver-sim.sim.phase-y
  "Phase Y: Evidence Fog and Attention Budget Constraint.

   Tests whether system correctness holds when:
   - 15% of disputes are hard/ambiguous to verify
   - Resolvers have a fixed attention budget per epoch (20 units)
   - Attackers can pay to inflate evidence complexity
   - Deep verification is 5-10x more costly than shallow

   Hypothesis to falsify:
     'The system maintains >80% correctness even with limited resolver
      budgets and attacker-driven evidence complexity escalation.'

   If correctness drops below 80% under realistic loads, the system
   needs attention-reward design changes before 90% confidence is claimed."
  (:require [resolver-sim.model.evidence-costs :as ec]
            [resolver-sim.model.difficulty :as diff]
            [resolver-sim.model.rng :as rng]))

;; ============ Dispute Complexity Distribution ============

(def complexity-distribution
  "Phase Y dispute mix (from PHASE_YZA spec):
   20% easy, 60% medium, 15% hard, 5% ambiguous"
  [{:type :easy      :weight 0.20 :evidence-units 5   :verify-units 1  :base-accuracy 0.95}
   {:type :medium    :weight 0.60 :evidence-units 15  :verify-units 3  :base-accuracy 0.82}
   {:type :hard      :weight 0.15 :evidence-units 30  :verify-units 8  :base-accuracy 0.65}
   {:type :ambiguous :weight 0.05 :evidence-units 100 :verify-units 99 :base-accuracy 0.52}])

(defn sample-dispute-type
  "Sample a dispute type from the Phase Y complexity distribution."
  [rng-val]
  (let [r (mod (Math/abs (long rng-val)) 100)]
    (cond
      (< r 20) (nth complexity-distribution 0)
      (< r 80) (nth complexity-distribution 1)
      (< r 95) (nth complexity-distribution 2)
      :else    (nth complexity-distribution 3))))

;; ============ Resolver Strategy Selection ============

(defn choose-strategy
  "Resolver selects deep, shallow, or guess based on available effort.

   Deep:    8-10 effort/dispute → 90% accuracy, 85% detection
   Shallow: 2-3 effort/dispute  → 70% accuracy, 40% detection
   Guess:   0 effort            → 52% accuracy,  0% detection"
  [effort-available-per-dispute]
  (cond
    (>= effort-available-per-dispute 8) {:name :deep    :effort 9  :accuracy-mult 1.10 :detection 0.85}
    (>= effort-available-per-dispute 2) {:name :shallow :effort 2  :accuracy-mult 0.85 :detection 0.40}
    :else                               {:name :guess   :effort 0  :accuracy-mult 0.63 :detection 0.00}))

(defn epoch-accuracy
  "Compute average accuracy across all disputes in an epoch given budget."
  [budget num-disputes attacker-complexity-add]
  (let [disputes (repeatedly num-disputes #(sample-dispute-type (rand)))
        effort-per-dispute (if (zero? num-disputes) budget
                             (/ budget num-disputes))]
    (reduce
      (fn [{:keys [correct total]} dispute]
        (let [effective-verify (+ (:verify-units dispute) attacker-complexity-add)
              actual-effort (min effort-per-dispute effective-verify)
              strategy (choose-strategy (/ (* budget actual-effort) (* num-disputes effective-verify)))
              accuracy (* (:base-accuracy dispute) (:accuracy-mult strategy))]
          {:correct (+ correct (if (> (rand) (- 1.0 accuracy)) 1 0))
           :total   (inc total)}))
      {:correct 0 :total 0}
      disputes)))

;; ============ Attacker Evidence Manipulation ============

(defn attacker-complexity-cost
  "Cost attacker pays to add complexity to evidence.

   +5 complexity → +20% evidence units
   +10 complexity → +false evidence layer (harder to detect)
   +20 complexity → truth structurally ambiguous"
  [complexity-level base-dispute-value]
  (case complexity-level
    :low    {:cost (* base-dispute-value 0.05) :complexity-add 1  :label "+5% complexity"}
    :medium {:cost (* base-dispute-value 0.10) :complexity-add 3  :label "+false layer"}
    :high   {:cost (* base-dispute-value 0.20) :complexity-add 10 :label "structurally ambiguous"}
    {:cost 0 :complexity-add 0 :label "no manipulation"}))

;; ============ Test Scenarios ============

(defn test-baseline-correctness
  "Baseline: 20 disputes, 20-unit budget, no attacker manipulation."
  []
  (println "\n📋 TEST 1: Baseline Correctness (light load, no manipulation)")
  (let [budget 20 n-disputes 20 complexity-add 0
        trials 200
        results (repeatedly trials #(epoch-accuracy budget n-disputes complexity-add))
        avg-accuracy (/ (reduce + (map #(/ (double (:correct %)) (:total %)) results)) trials)]
    (println (format "   Load: %d disputes, budget: %d units" n-disputes budget))
    (println (format "   Avg accuracy: %.1f%%" (* 100 avg-accuracy)))
    (println (format "   Status: %s" (if (>= avg-accuracy 0.80) "✅ PASS (>80%)" "❌ FAIL (<80%)")))
    {:scenario :baseline :avg-accuracy avg-accuracy :n-disputes n-disputes
     :budget budget :complexity-add complexity-add
     :class (if (>= avg-accuracy 0.80) "A" "C")}))

(defn test-heavy-load
  "Heavy load: 80 disputes, 20-unit budget (1/4 unit per dispute)."
  []
  (println "\n📋 TEST 2: Heavy Load (budget exhaustion)")
  (let [budget 20 n-disputes 80 complexity-add 0
        trials 200
        results (repeatedly trials #(epoch-accuracy budget n-disputes complexity-add))
        avg-accuracy (/ (reduce + (map #(/ (double (:correct %)) (:total %)) results)) trials)]
    (println (format "   Load: %d disputes, budget: %d units (%.2f per dispute)" n-disputes budget (/ (double budget) n-disputes)))
    (println (format "   Avg accuracy: %.1f%%" (* 100 avg-accuracy)))
    (println (format "   Status: %s" (if (>= avg-accuracy 0.80) "✅ PASS" "❌ FAIL")))
    {:scenario :heavy-load :avg-accuracy avg-accuracy :n-disputes n-disputes
     :budget budget :complexity-add complexity-add
     :class (if (>= avg-accuracy 0.80) "A" (if (>= avg-accuracy 0.65) "B" "C"))}))

(defn test-attacker-fog-low
  "Attacker adds low complexity (+1 unit per dispute). 40 disputes."
  []
  (println "\n📋 TEST 3: Attacker Fog — Low Manipulation")
  (let [{:keys [cost complexity-add label]} (attacker-complexity-cost :low 50000)
        budget 20 n-disputes 40 trials 200
        results (repeatedly trials #(epoch-accuracy budget n-disputes complexity-add))
        avg-accuracy (/ (reduce + (map #(/ (double (:correct %)) (:total %)) results)) trials)]
    (println (format "   Manipulation: %s (attacker pays $%.0f/dispute)" label cost))
    (println (format "   Load: %d disputes, budget: %d, complexity add: +%d" n-disputes budget complexity-add))
    (println (format "   Avg accuracy: %.1f%%" (* 100 avg-accuracy)))
    (println (format "   Status: %s" (if (>= avg-accuracy 0.80) "✅ PASS" "❌ FAIL")))
    {:scenario :fog-low :avg-accuracy avg-accuracy :complexity-add complexity-add
     :attacker-cost cost :class (if (>= avg-accuracy 0.80) "A" "C")}))

(defn test-attacker-fog-high
  "Attacker adds high complexity (+10 units per dispute). 40 disputes."
  []
  (println "\n📋 TEST 4: Attacker Fog — High Manipulation (structural ambiguity)")
  (let [{:keys [cost complexity-add label]} (attacker-complexity-cost :high 50000)
        budget 20 n-disputes 40 trials 200
        results (repeatedly trials #(epoch-accuracy budget n-disputes complexity-add))
        avg-accuracy (/ (reduce + (map #(/ (double (:correct %)) (:total %)) results)) trials)]
    (println (format "   Manipulation: %s (attacker pays $%.0f/dispute)" label cost))
    (println (format "   Load: %d disputes, budget: %d, complexity add: +%d" n-disputes budget complexity-add))
    (println (format "   Avg accuracy: %.1f%%" (* 100 avg-accuracy)))
    (println (format "   Status: %s" (if (>= avg-accuracy 0.80) "✅ PASS" "❌ FAIL")))
    {:scenario :fog-high :avg-accuracy avg-accuracy :complexity-add complexity-add
     :attacker-cost cost :class (if (>= avg-accuracy 0.80) "A" (if (>= avg-accuracy 0.65) "B" "C"))}))

(defn test-ambiguous-dispute-concentration
  "Attacker floods with hard/ambiguous disputes to exploit the 5% tail."
  []
  (println "\n📋 TEST 5: Ambiguous Dispute Concentration (tail exploitation)")
  (let [budget 20 n-disputes 30 trials 200
        ;; All disputes are ambiguous-type (attacker cherry-picks)
        targeted-accuracy (fn []
                            (let [effort-per (* (double budget) (/ 1.0 n-disputes))]
                              (reduce (fn [{:keys [correct total]} _]
                                        (let [d (last complexity-distribution)
                                              s (choose-strategy effort-per)
                                              acc (* (:base-accuracy d) (:accuracy-mult s))]
                                          {:correct (+ correct (if (> (rand) (- 1.0 acc)) 1 0))
                                           :total (inc total)}))
                                      {:correct 0 :total 0}
                                      (range n-disputes))))
        results (repeatedly trials targeted-accuracy)
        avg-accuracy (/ (reduce + (map #(/ (double (:correct %)) (:total %)) results)) trials)]
    (println (format "   Scenario: all %d disputes are ambiguous-type (attacker-selected)" n-disputes))
    (println (format "   Budget: %d units (%.2f per dispute)" budget (/ (double budget) n-disputes)))
    (println (format "   Avg accuracy: %.1f%%" (* 100 avg-accuracy)))
    (println (format "   Status: %s" (if (>= avg-accuracy 0.80) "✅ PASS" "❌ FAIL")))
    {:scenario :ambiguous-concentration :avg-accuracy avg-accuracy
     :class (if (>= avg-accuracy 0.80) "A" (if (>= avg-accuracy 0.65) "B" "C"))}))

;; ============ Full Phase Y Run ============

(defn run-phase-y-sweep
  "Run all Phase Y evidence fog tests. Returns summary."
  [_params]

  (println "\n📊 PHASE Y: EVIDENCE FOG & ATTENTION BUDGET TESTING")
  (println "   Hypothesis: >80% correctness survives budget caps + attacker complexity escalation")
  (println "")

  (let [r1 (test-baseline-correctness)
        r2 (test-heavy-load)
        r3 (test-attacker-fog-low)
        r4 (test-attacker-fog-high)
        r5 (test-ambiguous-dispute-concentration)

        all-results [r1 r2 r3 r4 r5]
        class-a (count (filter #(= "A" (:class %)) all-results))
        class-b (count (filter #(= "B" (:class %)) all-results))
        class-c (count (filter #(= "C" (:class %)) all-results))

        min-accuracy (apply min (map :avg-accuracy all-results))
        hypothesis-holds? (>= min-accuracy 0.80)]

    (println "\n═══════════════════════════════════════════════════")
    (println "📋 PHASE Y SUMMARY")
    (println "═══════════════════════════════════════════════════")
    (println (format "   Robust (A): %d  Acceptable (B): %d  Fragile (C): %d" class-a class-b class-c))
    (println (format "   Min accuracy across scenarios: %.1f%%" (* 100 min-accuracy)))
    (println (format "   Hypothesis holds? %s" (if hypothesis-holds? "✅ YES — system robust" "❌ NO — attention design needed")))
    (println "")
    (if hypothesis-holds?
      (do (println "   Confidence impact: +8% (evidence fog not a critical risk)")
          (println "   Recommendation: No changes needed; monitor under production load"))
      (do (println "   Confidence impact: 0% (issue found; needs attention reward design)")
          (println "   Recommendation: Add per-dispute effort rewards; increase budget for ambiguous cases")))
    (println "")

    {:results all-results
     :class-a class-a :class-b class-b :class-c class-c
     :min-accuracy min-accuracy
     :hypothesis-holds? hypothesis-holds?}))
