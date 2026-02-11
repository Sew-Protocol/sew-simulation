(ns resolver-sim.model.dispute
  "Dispute lifecycle and resolution mechanics."
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.model.economics :as econ]))

;; Dispute resolution for a single trial
(defn resolve-dispute
  "Resolve one dispute with given parameters and strategy.
   
   Returns:
   {:dispute-correct? bool        ; Whether resolver judged correctly
    :appeal-triggered? bool       ; Whether outcome is appealed
    :slashed? bool                ; Whether resolver caught and slashed
    :profit-honest integer        ; Profit if honest
    :profit-malice integer        ; Profit if malicious
    :strategy keyword}            ; Strategy used"
  [rng escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob]
  
  (let [fee (econ/calculate-fee escrow-wei fee-bps)
        bond (econ/calculate-bond escrow-wei bond-bps)
        
        ; Determine if resolver judges correctly (depends on strategy)
        verdict-correct?
        (case strategy
          :honest       true
          :lazy         (< (rng/next-double rng) 0.5)
          :malicious    (< (rng/next-double rng) 0.3)  ; Lie 70% of the time
          :collusive    (< (rng/next-double rng) 0.8))  ; Mostly honest
        
        ; Appeal rate depends on correctness
        appeal-prob
        (if verdict-correct? appeal-prob-correct appeal-prob-wrong)
        appealed?
        (< (rng/next-double rng) appeal-prob)
        
        ; Slashing: only if verdict is WRONG and detected
        base-detection-prob
        (case strategy
          :honest 0.01
          :lazy 0.02
          :malicious detection-prob
          :collusive 0.05)
        slashed?
        (and (not verdict-correct?) (< (rng/next-double rng) base-detection-prob))
        
        slashing-loss
        (if slashed? (* bond slash-mult) 0)
        
        ; Honest resolver: earns fee (always positive)
        profit-honest (long fee)
        
        ; Malicious resolver: earns fee from correct verdict, but loses bond if caught
        profit-malice (long (- fee slashing-loss))]
    
    {:dispute-correct? verdict-correct?
     :appeal-triggered? appealed?
     :slashed? slashed?
     :profit-honest profit-honest
     :profit-malice profit-malice
     :strategy strategy}))

(defn multiple-disputes
  "Run N consecutive disputes with same parameters.
   
   Returns aggregated statistics."
  [rng n-trials escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob]
  
  (let [results (repeatedly n-trials
                  #(resolve-dispute rng escrow-wei fee-bps bond-bps slash-mult
                                    strategy appeal-prob-correct appeal-prob-wrong
                                    detection-prob))
        profits-honest (map :profit-honest results)
        profits-malice (map :profit-malice results)
        mean-honest (double (/ (reduce + profits-honest) n-trials))
        mean-malice (double (/ (reduce + profits-malice) n-trials))
        appeal-count (count (filter :appeal-triggered? results))
        slash-count (count (filter :slashed? results))]
    
    {:n-trials n-trials
     :mean-profit-honest mean-honest
     :mean-profit-malice mean-malice
     :appeal-rate (double (/ appeal-count n-trials))
     :slash-rate (double (/ slash-count n-trials))
     :honest-wins (count (filter #(> (:profit-honest %) (:profit-malice %)) results))}))
