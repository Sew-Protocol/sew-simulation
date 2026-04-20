(ns resolver-sim.model.economics
  "Payoff and economic functions.
   
   All functions are pure: no side effects, deterministic given inputs."
  (:require [resolver-sim.model.rng :as rng])
  (:import [java.math BigDecimal]))

(defn calculate-fee
  "Calculate resolver fee based on escrow and fee rate (bps).
   Uses integer division (quot) to match contract_model/types compute-fee.

   Example:
   (calculate-fee 1000 150) ; 1000 wei escrow, 1.5% = 15 wei"
  [escrow-wei fee-bps]
  (quot (* escrow-wei fee-bps) 10000))

(defn calculate-bond
  "Calculate appeal bond based on escrow and bond rate (bps).
   Uses integer division (quot) to match contract_model/types compute-fee.

   Example:
   (calculate-bond 1000 700) ; 1000 wei escrow, 7% = 70 wei"
  [escrow-wei bond-bps]
  (quot (* escrow-wei bond-bps) 10000))

(defn calculate-slashing-loss
  "Calculate slashing loss if resolver is caught and slashed.
   
   Loss = bond * slash_multiplier"
  [bond-wei slash-multiplier]
  (* bond-wei slash-multiplier))

(defn honest-expected-value
  "Expected value for honest resolver.
   
   EV = fee * (1 - appeal_rate)
        + fee * appeal_rate * (1 - appeal_won_rate)
   
   Simplified: assume honest loses appeals rarely."
  [fee-wei appeal-prob-if-correct]
  (let [appeal-loss-prob (- 1 appeal-prob-if-correct)
        ev (* fee-wei appeal-loss-prob)]
    (max 0 ev)))

(defn malicious-expected-value
  "Expected value for malicious resolver.
   
   EV = fee - slashing_loss * detection_probability
   
   Malicious always tries to exploit, but may be caught."
  [fee-wei slashing-loss detection-prob]
  (let [net-profit (- fee-wei (* slashing-loss detection-prob))]
    net-profit))

(defn lazy-expected-value
  "Expected value for lazy resolver.
   
   EV = fee * (correct_probability * (1 - appeal_prob_correct)
               + wrong_probability * (1 - appeal_prob_wrong))"
  [fee-wei correct-prob appeal-prob-correct appeal-prob-wrong]
  (let [correct-ev (* correct-prob (- 1 appeal-prob-correct))
        wrong-ev (* (- 1 correct-prob) (- 1 appeal-prob-wrong))
        total-survival-prob (+ correct-ev wrong-ev)
        ev (* fee-wei total-survival-prob)]
    (max 0 ev)))

(defn collusive-expected-value
  "Expected value for collusive resolver (simplified).
   
   Assumes collusive resolvers coordinate to extract maximum profit.
   EV depends on coalition size + network enforcement.
   
   Simplified: EV = fee * coordination_bonus - slashing_loss * higher_detection"
  [fee-wei coalition-size detection-prob-increased]
  (let [; Larger coalition = lower per-member bonus but safer
        coordination-bonus (/ 1.2 (Math/log (+ 2 coalition-size)))
        ; More coordination = higher chance of detection
        effective-detection (min 0.5 detection-prob-increased)
        
        ev (- (* fee-wei coordination-bonus)
               (* fee-wei effective-detection))]
    (max 0 ev)))

(defn strategy-dominance-score
  "How much better is honest than malicious?
   
   score = ev_honest / ev_malicious
   
   score > 2.0 means honest is 2× better."
  [ev-honest ev-malicious]
  (if (zero? ev-malicious)
    (if (zero? ev-honest) 1.0 Double/POSITIVE_INFINITY)
    (/ ev-honest ev-malicious)))
