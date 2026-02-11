(ns resolver-sim.model.dispute
  "Dispute lifecycle and resolution mechanics."
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.model.economics :as econ]))

;; Dispute resolution for a single trial
;; Phase C: Support resolver bonds (capital at risk)
(defn resolve-dispute
  "Resolve one dispute with given parameters and strategy.
   
   Phase C adds resolver bonds: capital at risk per dispute.
   When slashed, loss is taken from bond (in addition to any fee loss).
   
   Returns:
   {:dispute-correct? bool        ; Whether resolver judged correctly
    :appeal-triggered? bool       ; Whether initial appeal happened
    :escalated? bool              ; Whether case escalated to L2
    :escalation-level int         ; Final level (0, 1, or 2)
    :slashed? bool                ; Whether resolver caught and slashed
    :profit-honest integer        ; Profit if honest
    :profit-malice integer        ; Profit if malicious
    :strategy keyword}            ; Strategy used"
  [rng escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob
   & {:keys [senior-resolver-skill escalation-fee-bps resolver-bond-bps]
      :or {senior-resolver-skill 0.95
           escalation-fee-bps 0
           resolver-bond-bps 100}}]  ; Phase C: 1% additional resolver bond
  
  (let [fee (econ/calculate-fee escrow-wei fee-bps)
        appeal-bond (econ/calculate-bond escrow-wei bond-bps)
        resolver-bond (econ/calculate-bond escrow-wei resolver-bond-bps)  ; Phase C: resolver's own bond
        
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
        
        ; Phase B: Escalation tracking (derived, no new RNG)
        escalation-level (if appealed? 1 0)
        escalated? (and appealed? (= strategy :malicious))
        
        ; Slashing: only if verdict is WRONG and detected
        base-detection-prob
        (case strategy
          :honest 0.01
          :lazy 0.02
          :malicious detection-prob
          :collusive 0.05)
        slashed?
        (and (not verdict-correct?) (< (rng/next-double rng) base-detection-prob))
        
        ; Phase C: Slashing loss comes from BOTH appeal bond AND resolver bond
        ; Total loss = (appeal_bond + resolver_bond) * slash_mult
        total-bond-slashing (econ/calculate-slashing-loss 
                            (+ appeal-bond resolver-bond) slash-mult)
        bond-loss
        (if slashed? total-bond-slashing 0)
        
        ; Honest resolver: earns fee (bond is never slashed)
        profit-honest (long fee)
        
        ; Malicious resolver: earns fee, but loses resolver-bond if caught
        profit-malice (long (- fee bond-loss))]
    
    {:dispute-correct? verdict-correct?
     :appeal-triggered? appealed?
     :escalated? escalated?
     :escalation-level escalation-level
     :slashed? slashed?
     :profit-honest profit-honest
     :profit-malice profit-malice
     :strategy strategy}))

(defn multiple-disputes
  "Run N consecutive disputes with same parameters.
   
   Returns aggregated statistics including Phase B escalation metrics."
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
        slash-count (count (filter :slashed? results))
        escalation-count (count (filter :escalated? results))
        l2-count (count (filter #(= (:escalation-level %) 2) results))]
    
    {:n-trials n-trials
     :mean-profit-honest mean-honest
     :mean-profit-malice mean-malice
     :appeal-rate (double (/ appeal-count n-trials))
     :slash-rate (double (/ slash-count n-trials))
     :escalation-rate (double (/ escalation-count n-trials))
     :l2-escalation-rate (double (/ l2-count n-trials))
     :honest-wins (count (filter #(> (:profit-honest %) (:profit-malice %)) results))}))
