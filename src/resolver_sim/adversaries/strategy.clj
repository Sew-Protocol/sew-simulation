(ns resolver-sim.adversaries.strategy
  "Pluggable adversary strategies for attack modeling.
   
   Adversaries choose whether and how to attack based on:
   - Available budget
   - Detection probability  
   - Expected profit
   - Available attack types (bribery, evidence, collusion)
   
   Phases:
   - Static (current): Fixed strategy, no learning
   - P (planned): Bribery markets with contingent escrow
   - Q (planned): Evidence spoofing with quality trade-offs
   - R (planned): Adaptive learning over epochs")

(defprotocol Adversary
  "Attack strategy that decides whether and how to attack."
  
  (should-attack? 
    [this dispute-params]
    "Decide whether to attack this dispute.
     Returns: true | false")
  
  (attack-type
    [this dispute-params]
    "Choose attack type given constraints.
     Returns: :none | :bribery | :evidence | :collusion")
  
  (budget-allocation
    [this dispute-params]
    "Allocate available budget across attack types.
     Returns: {:bribery ratio :evidence ratio :collusion ratio}
              where ratios sum to ~1.0")
  
  (expected-profit
    [this attack-type dispute-params]
    "Estimate profit from chosen attack.
     Returns: numeric expected value"))

;; ============ STATIC ATTACKER (Phase H baseline) ============

(deftype StaticAttacker []
  "Attacks with fixed probability, only bribery (detection cost = ∞).
   Behavior: 50% attack rate, always uses strategy parameter."
  
  Adversary
  
  (should-attack? [_ params]
    ;; Base attack rate: 50%
    (< (rand) 0.5))
  
  (attack-type [_ params]
    ;; In Phase H: only static strategy (honest/lazy/malicious)
    ;; Attacker doesn't choose; it's determined by params
    :none)  ;; Attack type handled by dispute/resolve-dispute directly
  
  (budget-allocation [_ params]
    ;; Static: no budget allocation needed
    {:bribery 0.0 :evidence 0.0 :collusion 0.0})
  
  (expected-profit [_ attack-type params]
    ;; Profit calculated by dispute/resolve-dispute
    0))

;; ============ BRIBERY ATTACKER (Phase P) ============

(deftype BriberyAttacker [bribe-cost-ratio]
  "Attacks using contingent bribes at realistic margin (30-50%).
   
   Model:
   - Attacker has bond B to risk
   - Offers: 'Vote X, or collateral C seized'
   - Juror chooses if: C + (1-p)·benefit > reward
   - Attacker pays: p · benefit where p = success probability
   - Margin: (1.0 to 1.5) accounts for attacker uncertainty
   
   Parameters:
   - bribe-cost-ratio: multiplier on base cost (1.3 = 30% markup)"
  
  Adversary
  
  (should-attack? [_ params]
    ;; Base attack rate: 50% (same as static)
    (< (rand) 0.5))
  
  (attack-type [_ params]
    ;; Phase P: Try bribery if affordable
    (let [available-budget (:attacker-budget params 0)
          bribery-cost (estimate-bribery-cost params bribe-cost-ratio)]
      (if (and (> available-budget 0)
               (< bribery-cost available-budget))
        :bribery
        :none)))
  
  (budget-allocation [_ params]
    ;; Phase P: All budget to bribery
    {:bribery 1.0 :evidence 0.0 :collusion 0.0})
  
  (expected-profit [_ attack-type params]
    (case attack-type
      :bribery (estimate-bribery-profit params)
      0)))

;; ============ EVIDENCE ATTACKER (Phase Q) ============

(deftype EvidenceAttacker [bribe-cost-ratio evidence-difficulty]
  "Attacks using evidence spoofing or bribery (whichever is cheaper).
   
   Model:
   - Can generate fake evidence (cost depends on difficulty + quality)
   - Can bribe jurors (cost depends on collateral margin)
   - Chooses whichever strategy is cheaper
   
   Parameters:
   - bribe-cost-ratio: multiplier for bribery cost
   - evidence-difficulty: :easy | :medium | :hard"
  
  Adversary
  
  (should-attack? [_ params]
    (< (rand) 0.5))
  
  (attack-type [_ params]
    ;; Phase Q: Compare costs, choose cheaper
    (let [bribery-cost (estimate-bribery-cost params bribe-cost-ratio)
          evidence-cost (estimate-evidence-cost params evidence-difficulty)
          available-budget (:attacker-budget params 0)]
      (cond
        (and (< evidence-cost bribery-cost)
             (< evidence-cost available-budget)) :evidence
        (< bribery-cost available-budget) :bribery
        :else :none)))
  
  (budget-allocation [_ params]
    ;; Phase Q: Allocate proportional to costs and success rates
    ;; Simple model: 60% evidence, 40% bribery (explore both)
    {:bribery 0.4 :evidence 0.6 :collusion 0.0})
  
  (expected-profit [_ attack-type params]
    (case attack-type
      :evidence (estimate-evidence-profit params)
      :bribery (estimate-bribery-profit params)
      0)))

;; ============ ADAPTIVE ATTACKER (Phase R) ============

(deftype AdaptiveAttacker [beliefs learning-rate]
  "Learns optimal strategy over epochs via multi-armed bandit.
   
   Model:
   - Starts with uniform beliefs about strategy success rates
   - Tries different strategies, observes outcomes
   - Updates beliefs via Bayesian learning
   - Epsilon-greedy: mostly exploit best, sometimes explore
   
   Parameters:
   - beliefs: vector [p-bribery p-evidence p-collusion]
   - learning-rate: [0.0-1.0] how fast to update (0.15 recommended)"
  
  Adversary
  
  (should-attack? [_ params]
    (< (rand) 0.5))
  
  (attack-type [_ params]
    ;; Phase R: Choose based on learned beliefs
    (let [current-beliefs (or (:beliefs params) beliefs)
          epsilon 0.1]  ;; 10% explore, 90% exploit
      (adaptive-strategy current-beliefs epsilon)))
  
  (budget-allocation [_ params]
    ;; Phase R: Allocate proportional to success beliefs
    (let [current-beliefs (or (:beliefs params) beliefs)]
      {:bribery (current-beliefs 0)
       :evidence (current-beliefs 1)
       :collusion (current-beliefs 2)}))
  
  (expected-profit [_ attack-type params]
    (estimate-profit-by-type attack-type params)))

;; ============ HELPER FUNCTIONS ============

(defn estimate-bribery-cost
  "Estimate cost to bribe jurors to force wrong outcome.
   
   Returns: cost in wei"
  [params bribe-cost-ratio]
  
  (let [escrow (:escrow-size params 10000)
        detection-prob (:fraud-detection-probability params 0.25)
        attack-benefit (* escrow 0.1)  ;; Attacker gains ~10% of escrow
        
        ;; Minimum cost: expected profit from attack
        min-cost (* attack-benefit (- 1 detection-prob))
        
        ;; Actual cost: apply margin for uncertainty
        actual-cost (* min-cost bribe-cost-ratio)]
    
    (long (Math/ceil actual-cost))))

(defn estimate-evidence-cost
  "Estimate cost to generate convincing fake evidence.
   
   Returns: cost in wei"
  [params evidence-difficulty]
  
  (let [base-cost (case evidence-difficulty
                    :easy 50
                    :medium 200
                    :hard 1000)
        
        ;; Quality: attacker tries for 50% conviction rate
        fake-quality 0.5
        quality-multiplier (if (< fake-quality 0.3)
                             1.0
                             (Math/pow 2 (- fake-quality 0.5)))
        
        ;; Verification time: default 30 min
        verification-time (:verification-time-minutes params 30)
        time-discount (/ 5.0 (max 5 verification-time))
        
        total-cost (* base-cost quality-multiplier time-discount)]
    
    (long (Math/ceil total-cost))))

(defn estimate-bribery-profit
  "Estimate profit if bribery attack succeeds."
  [params]
  
  (let [escrow (:escrow-size params 10000)
        bribe-cost (estimate-bribery-cost params 1.3)  ;; 30% markup
        attack-benefit (* escrow 0.1)]
    
    (max 0 (- attack-benefit bribe-cost))))

(defn estimate-evidence-profit
  "Estimate profit if evidence attack succeeds."
  [params]
  
  (let [escrow (:escrow-size params 10000)
        evidence-difficulty (:evidence-difficulty params :medium)
        evidence-cost (estimate-evidence-cost params evidence-difficulty)
        attack-benefit (* escrow 0.1)]
    
    (max 0 (- attack-benefit evidence-cost))))

(defn estimate-profit-by-type
  "Estimate profit for any attack type."
  [attack-type params]
  
  (case attack-type
    :bribery (estimate-bribery-profit params)
    :evidence (estimate-evidence-profit params)
    :collusion 0  ;; Phase S: TBD
    0))

(defn adaptive-strategy
  "Choose strategy via epsilon-greedy based on learned beliefs.
   
   Parameters:
   - beliefs: [p-bribery p-evidence p-collusion]
   - epsilon: [0.0-1.0] exploration rate
   
   Returns: :bribery | :evidence | :collusion"
  [beliefs epsilon]
  
  (let [strategies [:bribery :evidence :collusion]]
    (if (< (rand) epsilon)
      ;; Explore: try random strategy
      (rand-nth strategies)
      ;; Exploit: choose best based on beliefs
      (let [best-idx (apply max-key (fn [i] (beliefs i)) (range (count strategies)))]
        (nth strategies best-idx)))))

(defn bandit-update
  "Update beliefs based on observed outcomes (multi-armed bandit).
   
   Parameters:
   - old-beliefs: [p-bribery p-evidence p-collusion]
   - outcomes: sequence of [strategy success?] pairs
   - learning-rate: [0.0-1.0] how much to weight new data
   
   Returns: updated beliefs vector"
  [old-beliefs outcomes learning-rate]
  
  (let [strategies [:bribery :evidence :collusion]
        num-strategies (count strategies)]
    
    (vec (for [i (range num-strategies)]
           (let [strategy (strategies i)
                 ;; Find outcomes for this strategy
                 strategy-outcomes (filter #(= (% 0) strategy) outcomes)
                 successes (count (filter #(= (% 1) 1) strategy-outcomes))
                 trials (count strategy-outcomes)
                 
                 ;; Empirical success rate
                 empirical-p (if (> trials 0)
                              (/ successes (double trials))
                              0.5)  ;; Default if untested
                 
                 ;; Bayesian update: weighted average
                 new-p (+ (* (- 1 learning-rate) (old-beliefs i))
                         (* learning-rate empirical-p))]
             
             ;; Bound: keep within [0.1, 0.9] for diversity
             (max 0.1 (min 0.9 new-p)))))))
