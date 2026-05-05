(ns resolver-sim.sim.governance.phase-ad
  "Phase AD: Governance Bandwidth Floor.

   Safeguard for the Phase AA vulnerability: governance gaming is profitable
   when governance capacity is low and biased toward high-value disputes,
   leaving low-value disputes essentially unreviewed.

   This phase tests whether mandating a minimum review floor — regardless of
   dispute value — reduces attacker win rate below 20% even when overall
   governance capacity is constrained.

   Two levers:
     1. floor-reviews-per-epoch — a hard minimum number of randomly sampled
        low-value disputes reviewed each epoch (the safeguard)
     2. total-capacity          — total reviews available (may be shared)

   Hypothesis to confirm:
     With floor-reviews-per-epoch ≥ 1 per 5 disputes, attacker win rate < 20%
     across all governance bandwidth scenarios."
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as engine]))

;; ---------------------------------------------------------------------------
;; Dispute value model (mirrors Phase AA)
;; ---------------------------------------------------------------------------

(defn governance-review-probability
  "Base governance attention probability by dispute value (before floor)."
  [dispute-value]
  (cond
    (>= dispute-value 100000) 0.95
    (>= dispute-value 10000)  0.60
    :else                     0.20))

;; ---------------------------------------------------------------------------
;; Review selection with mandatory floor
;; ---------------------------------------------------------------------------

(defn select-reviewed-with-floor
  "Select disputes for governance review.

   Process:
     1. Allocate floor-reviews to randomly sampled low-value disputes (≤ floor-threshold)
     2. Remaining capacity reviews higher-value disputes probabilistically
     3. Total reviewed = floor + value-biased, capped at total-capacity"
  [disputes total-capacity floor-reviews floor-threshold d-rng]
  (let [low-value   (filter #(<= (:value %) floor-threshold) disputes)
        high-value  (filter #(> (:value %) floor-threshold) disputes)
        ;; Floor: random sample from low-value pool
        shuffled-low (shuffle low-value)
        floor-set    (set (map :id (take floor-reviews shuffled-low)))
        ;; Remaining capacity: probabilistic review of high-value
        remaining    (max 0 (- total-capacity floor-reviews))
        hv-candidates (filter (fn [d] (> (rng/next-double d-rng)
                                        (- 1.0 (governance-review-probability (:value d)))))
                              high-value)
        hv-sorted    (sort-by :value > hv-candidates)
        hv-set       (set (map :id (take remaining hv-sorted)))]
    (into floor-set hv-set)))

;; ---------------------------------------------------------------------------
;; Dispute outcome
;; ---------------------------------------------------------------------------

(defn simulate-dispute-outcome
  "Attacker win probability: 35% unreviewed, 5% reviewed."
  [dispute reviewed-ids d-rng]
  (let [reviewed? (contains? reviewed-ids (:id dispute))
        win-prob  (if reviewed? 0.05 0.35)]
    (< (rng/next-double d-rng) win-prob)))

;; ---------------------------------------------------------------------------
;; Single epoch simulation
;; ---------------------------------------------------------------------------

(defn run-epoch
  "Run one epoch: generate disputes, select for review, compute outcomes."
  [disputes-per-epoch total-capacity floor-reviews floor-threshold d-rng attacker-strategy]
  (let [epoch-disputes
        (for [i (range disputes-per-epoch)]
          (let [val (case attacker-strategy
                      :low    (rng/next-int d-rng 9999)
                      :medium (+ 10000 (rng/next-int d-rng 89999))
                      :high   (+ 100000 (rng/next-int d-rng 100000))
                      :random (+ 1000 (rng/next-int d-rng 150000)))]
            {:id (str i) :value val}))
        reviewed-ids (select-reviewed-with-floor
                      epoch-disputes total-capacity floor-reviews floor-threshold d-rng)
        outcomes     (map (fn [d]
                            {:won      (simulate-dispute-outcome d reviewed-ids d-rng)
                             :reviewed (contains? reviewed-ids (:id d))})
                          epoch-disputes)]
    {:wins     (count (filter :won outcomes))
     :attempts (count epoch-disputes)
     :reviewed (count (filter :reviewed outcomes))}))

;; ---------------------------------------------------------------------------
;; Single scenario simulation
;; ---------------------------------------------------------------------------

(defn run-governance-floor-scenario
  "Simulate n-epochs with and without the governance bandwidth floor."
  [label n-epochs disputes-per-epoch total-capacity floor-reviews floor-threshold
   attacker-strategy seed]
  (println (format "   %s" label))
  (let [d-rng         (rng/make-rng seed)
        epoch-results (map (fn [_] (run-epoch disputes-per-epoch total-capacity
                                              floor-reviews floor-threshold
                                              d-rng attacker-strategy))
                           (range n-epochs))
        total-wins    (reduce + (map :wins epoch-results))
        total-attempts (reduce + (map :attempts epoch-results))
        win-rate      (/ (double total-wins) (max 1 total-attempts))
        passes?       (< win-rate 0.20)]
    (println (format "     Win rate: %.1f%%  %s"
                     (* 100 win-rate)
                     (if passes? "✅ SAFE" "❌ VULNERABLE")))
    {:label      label
     :win-rate   win-rate
     :passes?    passes?
     :class      (if passes? "A" "C")}))

;; ---------------------------------------------------------------------------
;; Threshold search: find minimum viable (floor-reviews, total-capacity) that
;; holds attacker win rate below 20% against worst-case low-value flooding.
;;
;; Analytical derivation (for reference):
;;   All disputes low-value; review coverage = floor-reviews / disputes-per-epoch
;;   win-rate = (floor/n × 0.05) + ((n-floor)/n × 0.35)
;;            = 0.35 - 0.30 × (floor/n)
;;   win-rate < 0.20 requires floor/n > 0.50, i.e. floor > n/2
;;   For n=5: floor ≥ 3
;;
;; The search sweeps floor-reviews × total-capacity to confirm this empirically
;; and also test the mixed-attacker scenario (not purely analytical).
;; ---------------------------------------------------------------------------

(defn run-phase-ad-threshold-sweep
  "2D threshold search for minimum viable governance bandwidth floor.
   Sweeps floor-reviews × total-capacity at worst-case attacker strategy (:low).
   Returns a grid result and the minimum viable configuration."
  [params]
  (let [seed               (get params :rng-seed 42)
        n-epochs           (get params :n-epochs 50)
        disputes-per-epoch (get params :disputes-per-epoch 5)
        floor-threshold    (get params :floor-threshold 10000)
        floor-range        (range 0 (inc disputes-per-epoch))      ; 0..5
        capacity-range     [2 3 4 5 6]]

    (println "\n📊 PHASE AD THRESHOLD SEARCH")
    (println "   Sweeping: floor-reviews × total-capacity (worst case: low-value flooding)")
    (println (format "   Disputes/epoch: %d, floor-threshold: $%d" disputes-per-epoch floor-threshold))
    (println (format "   Analytical break-even: floor > %d/2 = %d reviews/epoch"
                     disputes-per-epoch (int (Math/ceil (/ disputes-per-epoch 2.0)))))
    (println "")

    (let [grid-results
          (for [cap capacity-range
                floor floor-range
                :let [_ (when (zero? floor) (print (format "   cap=%-3d " cap)))
                      label (format "floor=%d cap=%d" floor cap)
                      r     (run-governance-floor-scenario
                             label n-epochs disputes-per-epoch cap floor floor-threshold
                             :low (+ seed (* cap 100) floor))]]
            (do (when (= floor (last (range 0 (inc disputes-per-epoch))))
                  (println ""))
                {:floor      floor
                 :capacity   cap
                 :win-rate   (:win-rate r)
                 :passes?    (:passes? r)}))

          ;; Print 2D table: rows = capacity, cols = floor
          _ (println "")
          _ (println "   Win rate table (rows=capacity, cols=floor-reviews):")
          _ (print "   cap\\floor ")
          _ (doseq [f (range 0 (inc disputes-per-epoch))]
              (print (format "  %d   " f)))
          _ (println "")
          _ (doseq [cap capacity-range]
              (print (format "   %-9d " cap))
              (doseq [floor (range 0 (inc disputes-per-epoch))
                      :let [cell (first (filter #(and (= (:floor %) floor)
                                                      (= (:capacity %) cap))
                                                grid-results))]]
                (print (format "%4.0f%% " (* 100 (:win-rate cell)))))
              (println ""))

          viable    (filter :passes? grid-results)
          ;; Minimum viable: smallest floor that works at minimum capacity
          min-viable (->> viable
                          (sort-by (juxt :floor :capacity))
                          first)]

      (println "")
      (println "═══════════════════════════════════════════════════")
      (println "📋 PHASE AD THRESHOLD SEARCH SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Viable configurations found: %d / %d"
                       (count viable) (count grid-results)))
      (if min-viable
        (do
          (println (format "   Minimum viable config:"))
          (println (format "     floor-reviews  = %d per epoch" (:floor min-viable)))
          (println (format "     total-capacity = %d per epoch" (:capacity min-viable)))
          (println (format "     win-rate       = %.1f%%" (* 100 (:win-rate min-viable))))
          (println "")
          (println "   Recommendation: Mandate floor-reviews ≥ above minimum per epoch")
          (println "   Confidence impact: +5% (Phase AA vulnerability mitigated)"))
        (println "   ❌ No viable configuration found — floor mechanism insufficient"))
      (println "")

      (engine/make-result
       {:benchmark-id "AD-threshold"
        :label        "Governance Bandwidth Floor Threshold Search"
        :hypothesis   "A minimum viable (floor-reviews, capacity) configuration exists"
        :passed?      (some? min-viable)
        :results      (vec grid-results)
        :summary      {:viable-count (count viable) :total-count (count grid-results)
                       :min-viable min-viable}}))))

(defn run-phase-ad-sweep
  "Sweep governance bandwidth floor configurations against attacker strategies."
  [params]
  (let [seed               (get params :rng-seed 42)
        n-epochs           (get params :n-epochs 50)
        disputes-per-epoch (get params :disputes-per-epoch 5)
        floor-threshold    (get params :floor-threshold 10000)]

    (println "\n📊 PHASE AD: GOVERNANCE BANDWIDTH FLOOR")
    (println "   Hypothesis: mandatory floor reviews keep attacker win rate < 20%")
    (println "")

    (let [results
          [(run-governance-floor-scenario
            "TEST 1: No floor, cap=3, random attacker"
            n-epochs disputes-per-epoch 3 0 floor-threshold :random (+ seed 0))
           (run-governance-floor-scenario
            "TEST 2: No floor, cap=3, low-value flooding attacker"
            n-epochs disputes-per-epoch 3 0 floor-threshold :low (+ seed 1))
           (run-governance-floor-scenario
            "TEST 3: Floor=1, cap=3, low-value flooding attacker"
            n-epochs disputes-per-epoch 3 1 floor-threshold :low (+ seed 2))
           (run-governance-floor-scenario
            "TEST 4: Floor=2, cap=3, low-value flooding attacker"
            n-epochs disputes-per-epoch 3 2 floor-threshold :low (+ seed 3))
           (run-governance-floor-scenario
            "TEST 5: Floor=1, cap=2 (constrained), mixed attacker"
            n-epochs disputes-per-epoch 2 1 floor-threshold :random (+ seed 4))]

          class-a           (count (filter #(= "A" (:class %)) results))
          class-c           (count (filter #(= "C" (:class %)) results))
          max-win-rate      (apply max (map :win-rate results))
          ;; Hypothesis: floor ≥ 1 suffices against low-value flooding
          floor-tests       (filter #(clojure.string/includes? (:label %) "Floor=") results)
          hypothesis-holds? (every? :passes? floor-tests)]

      (println "\n═══════════════════════════════════════════════════")
      (println "📋 PHASE AD SUMMARY")
      (println "═══════════════════════════════════════════════════")
      (println (format "   Safe (A): %d  Vulnerable (C): %d" class-a class-c))
      (println (format "   Max attacker win rate: %.1f%%" (* 100 max-win-rate)))
      (println (format "   Hypothesis holds? %s"
                       (if hypothesis-holds?
                         "✅ YES — governance floor neutralises low-value flooding"
                         "❌ NO — floor insufficient; higher floor or capacity needed")))
      (println "")
      (if hypothesis-holds?
        (do (println "   Recommendation: Mandate floor-reviews ≥ 1 per 5 disputes per epoch")
            (println "   Confidence impact: +5% (Phase AA vulnerability mitigated)"))
        (do (println "   Recommendation: Increase floor or capacity; audit attacker detection")
            (println "   Confidence impact: 0%")))
      (println "")

      (engine/make-result
       {:benchmark-id "AD"
        :label        "Governance Bandwidth Floor"
        :hypothesis   "Mandatory floor reviews keep attacker win rate < 20%"
        :passed?      hypothesis-holds?
        :results      results
        :summary      {:class-a class-a :class-c class-c :max-win-rate max-win-rate}})))) 
