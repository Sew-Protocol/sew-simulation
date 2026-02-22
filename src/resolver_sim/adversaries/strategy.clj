(ns resolver-sim.adversaries.strategy
  "Pluggable adversary strategies for attack modeling.")

;; ============ HELPER FUNCTIONS ============

(defn estimate-bribery-cost
  "Estimate bribery cost from parameters."
  [params bribe-cost-ratio]
  (let [escrow (:escrow-size params 10000)
        detection (:fraud-detection-probability params 0.0)
        cost (* escrow detection (- bribe-cost-ratio 1.0))]
    (long (Math/ceil cost))))

(defn estimate-bribery-profit
  "Estimate bribery profit from parameters."
  [params]
  (:escrow-size params 10000))

(defn estimate-evidence-cost
  "Estimate evidence cost from parameters."
  [params difficulty]
  (case difficulty
    :easy 1000
    :medium 5000
    :hard 15000
    5000))

(defn estimate-evidence-profit
  "Estimate evidence profit from parameters."
  [params]
  (:escrow-size params 10000))

;; ============ PROTOCOL ============

(defprotocol Adversary
  "Attack strategy that decides whether and how to attack."
  
  (should-attack? 
    [this dispute-params]
    "Decide whether to attack this dispute.")
  
  (attack-type
    [this dispute-params]
    "Choose attack type given constraints.")
  
  (budget-allocation
    [this dispute-params]
    "Allocate available budget across attack types.")
  
  (expected-profit
    [this attack-type dispute-params]
    "Estimate profit from chosen attack."))

;; ============ STATIC ATTACKER (Phase H baseline) ============

(deftype StaticAttacker []
  Adversary
  
  (should-attack? [_ params]
    (< (rand) 0.5))
  
  (attack-type [_ params]
    :none)
  
  (budget-allocation [_ params]
    {:bribery 0.0 :evidence 0.0 :collusion 0.0})
  
  (expected-profit [_ attack-type params]
    0))

;; ============ BRIBERY ATTACKER (Phase P) ============

(deftype BriberyAttacker [bribe-cost-ratio]
  Adversary
  
  (should-attack? [_ params]
    (< (rand) 0.5))
  
  (attack-type [_ params]
    (let [available-budget (:attacker-budget params 0)
          bribery-cost (estimate-bribery-cost params bribe-cost-ratio)]
      (if (and (> available-budget 0)
               (< bribery-cost available-budget))
        :bribery
        :none)))
  
  (budget-allocation [_ params]
    {:bribery 1.0 :evidence 0.0 :collusion 0.0})
  
  (expected-profit [_ attack-type params]
    (case attack-type
      :bribery (estimate-bribery-profit params)
      0)))

;; ============ EVIDENCE ATTACKER (Phase Q) ============

(deftype EvidenceAttacker [bribe-cost-ratio evidence-difficulty]
  Adversary
  
  (should-attack? [_ params]
    (< (rand) 0.5))
  
  (attack-type [_ params]
    (let [bribery-cost (estimate-bribery-cost params bribe-cost-ratio)
          evidence-cost (estimate-evidence-cost params evidence-difficulty)
          available-budget (:attacker-budget params 0)]
      (cond
        (and (< evidence-cost bribery-cost)
             (< evidence-cost available-budget)) :evidence
        (< bribery-cost available-budget) :bribery
        :else :none)))
  
  (budget-allocation [_ params]
    {:bribery 0.4 :evidence 0.6 :collusion 0.0})
  
  (expected-profit [_ attack-type params]
    (case attack-type
      :evidence (estimate-evidence-profit params)
      :bribery (estimate-bribery-profit params)
      0)))

;; ============ ADAPTIVE ATTACKER (Phase R) ============

(deftype AdaptiveAttacker [beliefs learning-rate]
  Adversary
  
  (should-attack? [_ params]
    (some #(> % 0) beliefs))
  
  (attack-type [_ params]
    (if (< (rand) 0.1)
      (rand-nth [:bribery :evidence :collusion])
      (let [best-idx (.indexOf beliefs (apply max beliefs))]
        (nth [:bribery :evidence :collusion] best-idx))))
  
  (budget-allocation [_ params]
    (let [total (apply + beliefs)
          normalize #(/ (double %) (double total))]
      {:bribery (normalize (nth beliefs 0))
       :evidence (normalize (nth beliefs 1))
       :collusion (normalize (nth beliefs 2))}))
  
  (expected-profit [_ attack-type params]
    (case attack-type
      :bribery (nth beliefs 0)
      :evidence (nth beliefs 1)
      :collusion (nth beliefs 2)
      0)))
