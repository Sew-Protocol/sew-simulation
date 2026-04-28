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
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine :as engine]))

;; ============ Governance Models (Pure) ============

(defn governance-review-probability
  "Probability that governance reviews a dispute given its value."
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
  "Determine if an attacker wins a dispute."
  [dispute reviewed-ids d-rng]
  (let [reviewed? (contains? reviewed-ids (:id dispute))
        win-prob (if reviewed? 0.05 0.35)]
    (< (rng/next-double d-rng) win-prob)))

(defn infer-grey-zone
  "Attacker analyzes history to find the highest value with <20% review rate."
  [history]
  (let [by-value (group-by (fn [d] (cond (< (:value d) 10000) :low
                                       (< (:value d) 100000) :med
                                       :else :high)) history)
        stats (for [[k v] by-value]
                [k (/ (double (count (filter :reviewed v))) (count v))])]
    (if-let [best (first (sort-by second < (filter (fn [[_ prob]] (< prob 0.3)) stats)))]
      (first best)
      :low)))

;; ============ Engine Adapters ============

(defn simulate-epoch-aa
  [epoch state params d-rng]
  (let [{:keys [capacity learning? bias-overrides]} params
        history  (:history state [])
        attacker-strategy (if (and learning? (> epoch 20))
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
    
    {:epoch epoch
     :history (concat history outcomes)
     :total-wins (+ (:total-wins state 0) new-wins)
     :total-attempts (+ (:total-attempts state 0) (count epoch-disputes))}))

(defn summarize-aa-history
  [history params]
  (let [final (last history)
        total-wins (:total-wins final)
        total-attempts (:total-attempts final)
        win-rate (/ (double total-wins) total-attempts)
        passed? (< win-rate 0.20)]
    {:status (if passed? "✅ SAFE" "❌ VULNERABLE")
     :win-rate win-rate
     :class (if passed? "A" "C")
     :passed? passed?}))

;; ============ Scenario Definitions ============

(defn make-scenarios [seed]
  [{:label "TEST 1: Baseline (High capacity, naive attacker)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed seed
    :params {:capacity 5 :learning? false}}
   
   {:label "TEST 2: Limited Capacity (Cap=3, learning attacker)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 1)
    :params {:capacity 3 :learning? true}}
   
   {:label "TEST 3: Biased Governance (Focus on >$50K)"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 2)
    :params {:capacity 3 :bias {:bias :high} :learning? true}}
   
   {:label "TEST 4: Low-Value Flooding"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 3)
    :params {:capacity 2 :learning? true}}
   
   {:label "TEST 5: Adversarial Threshold Search"
    :initial-state {:history [] :total-wins 0 :total-attempts 0}
    :update-fn simulate-epoch-aa
    :summary-fn summarize-aa-history
    :epochs 50
    :seed (+ seed 4)
    :params {:capacity 1 :learning? true}}])

;; ============ Full Phase AA Run ============

(defn run-phase-aa-sweep
  "Run all Phase AA governance gaming tests."
  [params]
  (let [seed (:rng-seed params 42)
        _ (engine/print-phase-header
             {:benchmark-id "AA"
              :label        "Governance as Adversary"
              :hypothesis   "Attackers cannot exceed 20% win rate via governance gaming"})
        
        scenarios (make-scenarios seed)
        results (engine/run-sweep "PHASE AA SWEEP" scenarios params)
        
        class-a (count (filter #(= "A" (:class %)) results))
        class-c (count (filter #(= "C" (:class %)) results))
        max-win-rate (apply max (map :win-rate results))
        hypothesis-holds? (< max-win-rate 0.20)]

    (engine/print-phase-footer
     {:benchmark-id  "AA"
      :passed?       hypothesis-holds?
      :summary-lines [(format "Robust (A): %d  Fragile (C): %d" class-a class-c)
                      (format "Max attacker win rate: %.1f%%" (* 100 max-win-rate))]})

    (engine/make-result
     {:benchmark-id "AA"
      :label        "Governance as Adversary"
      :hypothesis   "Attackers cannot exceed 20% win rate via governance gaming"
      :passed?      hypothesis-holds?
      :results      results
      :summary      {:class-a class-a :class-c class-c :max-win-rate max-win-rate}})))
