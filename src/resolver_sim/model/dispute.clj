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
   Phase G adds slashing delays and control baseline support.
   Phase H adds realistic bond mechanics: immediate freeze, unstaking delays, appeal windows.
   
   Returns:
   {:dispute-correct? bool        ; Whether resolver judged correctly
    :appeal-triggered? bool       ; Whether initial appeal happened
    :escalated? bool              ; Whether case escalated to L2
    :escalation-level int         ; Final level (0, 1, or 2)
    :slashed? bool                ; Whether resolver caught and slashed
    :slashing-pending? bool       ; Phase G: slashing is scheduled but delayed
    :frozen? bool                 ; Phase H: account frozen at detection
    :escaped? bool                ; Phase H: did resolver unstake before penalties?
    :slashing-delay-weeks int     ; Phase G: weeks until slashing takes effect (0 = immediate)
    :slashing-reason keyword      ; Reason for slashing (timeout/reversal/fraud or nil)
    :profit-honest integer        ; Profit if honest
    :profit-malice integer        ; Profit if malicious (accounts for freeze/appeal/unstake delays)
    :strategy keyword}            ; Strategy used"
  [rng escrow-wei fee-bps bond-bps slash-mult strategy
   appeal-prob-correct appeal-prob-wrong detection-prob
   & {:keys [senior-resolver-skill escalation-fee-bps resolver-bond-bps l2-detection-prob
             slashing-detection-delay-weeks allow-slashing?
             unstaking-delay-days freeze-on-detection? freeze-duration-days appeal-window-days
             detection-type timeout-detection-probability reversal-detection-probability]
      :or {senior-resolver-skill 0.95
           escalation-fee-bps 0
           resolver-bond-bps 100
           l2-detection-prob 0
           slashing-detection-delay-weeks 0
           allow-slashing? true
           unstaking-delay-days 14
           freeze-on-detection? true
           freeze-duration-days 3
           appeal-window-days 7
           detection-type :fraud
           timeout-detection-probability 0.0
           reversal-detection-probability 0.0}}]
  
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
        
        ; Slashing: only if verdict is WRONG and detected at L1
        base-detection-prob
        (case strategy
          :honest 0.01
          :lazy 0.02
          :malicious detection-prob
          :collusive 0.05)
        l1-slashed?
        (and (not verdict-correct?) (< (rng/next-double rng) base-detection-prob))
        
        ; Phase E1: L2 (Kleros) detection - additional catch by L2 if appealed
        l2-slashed?
        (if (and appealed? (not verdict-correct?) (> l2-detection-prob 0))
          (< (rng/next-double rng) l2-detection-prob)
          false)
        
        ; Final slashing: caught by L1 OR L2
        slashed-detected? (and allow-slashing? (or l1-slashed? l2-slashed?))
        
        ; Phase C: Slashing loss (unchanged)
        total-bond-slashing (econ/calculate-slashing-loss 
                            (+ appeal-bond resolver-bond) slash-mult)
        bond-loss
        (if slashed-detected? total-bond-slashing 0)
        
        ; Phase D: Slashing reason (derived from state to avoid RNG changes)
        slash-reason
        (if slashed-detected?
          (let [state-hash (Math/abs (hash [strategy verdict-correct? appealed? bond-loss]))
                reason-pct (rem state-hash 100)]
            (cond
              (< reason-pct 50) :timeout      ; 50%
              (< reason-pct 80) :reversal     ; 30%
              :else             :fraud))      ; 20%
          nil)
        
        ; Phase G: Slashing delay handling
        ; If delay > 0, slashing is scheduled but not applied immediately
        ; Malicious can earn fees during delay before loss is applied
        slashing-pending? (and slashed-detected? (> slashing-detection-delay-weeks 0))
        delay-weeks (if slashing-pending? slashing-detection-delay-weeks 0)
        
        ; Phase H: Realistic bond mechanics
        ; Immediate freeze on detection (vs delayed slashing)
        ; Then appeal window, then penalty applied, then unstaking delay
        frozen? (and slashed-detected? freeze-on-detection?)
        
        ; Timeline calculation:
        ; T0: Fraud detected → frozen immediately (if freeze-on-detection? = true)
        ; T0 + freeze-duration: Freeze expires, can request unstake
        ; T0 + freeze-duration + appeal-window: Slash executes (to in-protocol bond)
        ; T0 + freeze-duration + appeal-window + unstaking-delay: Can actually withdraw
        ;
        ; Since slash executes DURING unstaking delay, penalty is collected from in-protocol funds
        ; Resolver cannot escape unless unstaking-delay > (freeze-duration + appeal-window)
        ; With realistic values: 3 + 7 + 14 = 24 days to fully escape
        ; This makes escape nearly impossible if detection happens early
        
        can-escape? (and frozen? (< unstaking-delay-days (+ freeze-duration-days appeal-window-days)))
        escaped? (if frozen? (not can-escape?) false)
        
        ; Phase H: Penalty is always applied to in-protocol bond (never escapes if frozen immediately)
        ; Only defer penalty application if they somehow escape during the tiny window
        effective-bond-loss (if (and slashed-detected? frozen? (not escaped?)) 
                              bond-loss 
                              (if slashing-pending? 0 bond-loss))
        
        ; Honest resolver: earns fee (never slashed unless allow-slashing? is true)
        profit-honest (long fee)
        
        ; Malicious resolver: earns fee, but loses bonds if caught
        ; With Phase H mechanics, escape is nearly impossible if frozen immediately
        profit-malice (long (- fee effective-bond-loss))]
    
    {:dispute-correct? verdict-correct?
     :appeal-triggered? appealed?
     :l2-detected? l2-slashed?
     :escalated? escalated?
     :escalation-level escalation-level
     :slashed? slashed-detected?
     :frozen? frozen?
     :escaped? escaped?
     :slashing-pending? slashing-pending?
     :slashing-delay-weeks delay-weeks
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
