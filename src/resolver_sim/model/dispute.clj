(ns resolver-sim.model.dispute
  "Dispute lifecycle and resolution mechanics."
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.model.economics :as econ]))

;; Dispute resolution for a single trial
;; Phase D: Track slashing reasons (timeout/reversal/fraud) without RNG changes
(defn resolve-dispute
  "Resolve one dispute with given parameters and strategy.
   
   Phase D adds slashing reason tracking (timeout/reversal/fraud).
   Reasons are deterministically derived from existing state.
   
   Returns:
   {:dispute-correct? bool        ; Whether resolver judged correctly
    :appeal-triggered? bool       ; Whether initial appeal happened
    :escalated? bool              ; Whether case escalated to L2
    :escalation-level int         ; Final level (0, 1, or 2)
    :slashed? bool                ; Whether resolver caught and slashed
    :slashing-reason keyword      ; Reason for slashing (timeout/reversal/fraud or nil)
    :profit-honest integer        ; Profit if honest
    :profit-malice integer        ; Profit if malicious
    :strategy keyword}            ; Strategy used"
  [rng escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob
   & {:keys [senior-resolver-skill escalation-fee-bps resolver-bond-bps]
      :or {senior-resolver-skill 0.95
           escalation-fee-bps 0
           resolver-bond-bps 100}}]
  
  (let [fee (econ/calculate-fee escrow-wei fee-bps)
        appeal-bond (econ/calculate-bond escrow-wei bond-bps)
        resolver-bond (econ/calculate-bond escrow-wei resolver-bond-bps)
        
        ; Determine if resolver judges correctly (depends on strategy)
        verdict-correct?
        (case strategy
          :honest       true
          :lazy         (< (rng/next-double rng) 0.5)
          :malicious    (< (rng/next-double rng) 0.3)
          :collusive    (< (rng/next-double rng) 0.8))
        
        ; Appeal rate depends on correctness
        appeal-prob
        (if verdict-correct? appeal-prob-correct appeal-prob-wrong)
        appealed?
        (< (rng/next-double rng) appeal-prob)
        
        ; Phase B: Escalation tracking
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
        
        ; Phase C: Slashing loss (unchanged)
        total-bond-slashing (econ/calculate-slashing-loss 
                            (+ appeal-bond resolver-bond) slash-mult)
        bond-loss
        (if slashed? total-bond-slashing 0)
        
        ; Phase D: Slashing reason (derived from state to avoid RNG changes)
        ; Assign based on: verdict-correct + appealed + strategy + bond values
        ; This ensures deterministic, reproducible reason assignment
        slash-reason
        (if slashed?
          (let [;; Use hash of state to deterministically pick reason
                ;; This preserves RNG sequence (no new calls) while varying reason
                state-hash (Math/abs (hash [strategy verdict-correct? appealed? bond-loss]))
                reason-pct (rem state-hash 100)]
            (cond
              (< reason-pct 50) :timeout      ; 50%
              (< reason-pct 80) :reversal     ; 30%
              :else             :fraud))      ; 20%
          nil)
        
        ; Honest resolver: earns fee
        profit-honest (long fee)
        
        ; Malicious resolver: earns fee, but loses bonds if caught
        profit-malice (long (- fee bond-loss))]
    
    {:dispute-correct? verdict-correct?
     :appeal-triggered? appealed?
     :escalated? escalated?
     :escalation-level escalation-level
     :slashed? slashed?
     :slashing-reason slash-reason
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
