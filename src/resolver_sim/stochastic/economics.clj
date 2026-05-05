(ns resolver-sim.stochastic.economics
  "Payoff and economic functions.
   
   All functions are pure: no side effects, deterministic given inputs."
  (:require [resolver-sim.stochastic.rng :as rng])
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

   EV = fee + fraud-upside * fraud-success-rate - slashing-loss * detection-probability

   fraud-upside is the escrow-diversion gain: escrow × (1 − fee-rate).
   When fraud-success-rate=0.0 (the default), this reduces to the original
   EV = fee - slashing-loss * detection-probability, keeping backward compatibility.

   IMPORTANT: This is protocol income only. It does not model the full economic
   gain to a colluding *party* who receives the misdirected escrow."
  ([fee-wei slashing-loss detection-prob]
   (malicious-expected-value fee-wei slashing-loss detection-prob 0 0.0))
  ([fee-wei slashing-loss detection-prob fraud-upside fraud-success-rate]
   (let [expected-fraud-gain (* fraud-upside fraud-success-rate)
         net-profit (+ fee-wei expected-fraud-gain (- (* slashing-loss detection-prob)))]
     net-profit)))

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
  "Expected value for collusive resolver.

   EV = fee * colluder-gain-rate - fee * effective-detection

   colluder-gain-rate drives extra profit from coordinated wrong verdicts.
   Default 1.2 reproduces the original model's coordination bonus at coalition=1.
   Calibrated value from ring-attack trace: use ~1.15 (Phase AI escalation-trap).

   To use the original hard-coded formula: omit colluder-gain-rate (or pass nil).
   To calibrate from trace data: pass the measured gain multiplier directly."
  ([fee-wei coalition-size detection-prob-increased]
   (collusive-expected-value fee-wei coalition-size detection-prob-increased nil))
  ([fee-wei coalition-size detection-prob-increased colluder-gain-rate]
   (let [; colluder-gain-rate nil → fall back to the original log-based formula
         gain-rate (or colluder-gain-rate
                       (/ 1.2 (Math/log (+ 2 coalition-size))))
         effective-detection (min 0.5 detection-prob-increased)
         ev (- (* fee-wei gain-rate)
               (* fee-wei effective-detection))]
     (max 0 ev))))

(defn strategy-dominance-score
  "How much better is honest than malicious?

   score = ev_honest / ev_malicious

   score > 2.0 means honest is 2× better."
  [ev-honest ev-malicious]
  (if (zero? ev-malicious)
    (if (zero? ev-honest) 1.0 Double/POSITIVE_INFINITY)
    (/ ev-honest ev-malicious)))

(defn worst-case-fraud-success-rate
  "Worst-case fraud-success-rate: every undetected malicious resolver diverts funds.
   fraud-success-rate = 1 - detection-prob.

   This is the correct default for economic security analysis. It means:
   'if not caught, the malicious resolver captures the escrow.'
   Setting fraud-success-rate=0.0 (the original default) only models protocol income."
  [detection-prob]
  (max 0.0 (- 1.0 detection-prob)))

(defn breakeven-detection
  "Minimum detection probability for honest EV to exceed full malicious EV.

   Derivation (worst-case model: fraud-success-rate = 1 - detection-prob):
     malice-EV = fee + (1-d) × (escrow - fee) - d × bond-loss
     honest-EV ≈ fee

   Setting honest-EV = malice-EV and solving for d:
     d = (escrow - fee) / (bond-loss + escrow - fee)

   If current detection-prob < breakeven-detection, bond deterrence alone is insufficient.
   The protocol's economic security relies on the state machine constraining
   fraud-success-rate (via invariants: funds-conservation, no-double-release)."
  [escrow-wei fee-wei bond-loss]
  (let [escrow-net (- escrow-wei fee-wei)]
    (double (/ escrow-net (+ bond-loss escrow-net)))))
