(ns resolver-sim.sim.batch
  "Batch runner: aggregate N trials into summary statistics."
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.model.dispute :as dispute]))

(defn mean [vals]
  (if (empty? vals) 0 (double (/ (reduce + vals) (count vals)))))

(defn variance [vals mean-val]
  (if (<= (count vals) 1)
    0.0
    (double (/ (reduce + (map #(Math/pow (- % mean-val) 2) vals))
               (dec (count vals))))))

(defn std-dev [vals mean-val]
  (Math/sqrt (variance vals mean-val)))

(defn quantile [sorted-vals q]
  "Calculate q-quantile (0-1) from sorted sequence."
  (if (empty? sorted-vals)
    0
    (let [idx (* q (dec (count sorted-vals)))]
      (if (integer? idx)
        (nth sorted-vals (int idx))
        (let [lo (int (Math/floor idx))
              hi (int (Math/ceil idx))
              frac (- idx lo)]
          (+ (* (- 1 frac) (nth sorted-vals lo))
             (* frac (nth sorted-vals hi))))))))

(defn run-batch
  "Run N trials with given parameters and return aggregated stats.
   Phase B: Includes escalation metrics."
  [rng n-trials params]
  (let [results
        (repeatedly n-trials
          #(dispute/resolve-dispute
            rng (:escrow-size params 10000)
            (:resolver-fee-bps params)
            (:appeal-bond-bps params)
            (:slash-multiplier params)
            (:strategy params :honest)
            (:appeal-probability-if-correct params)
            (:appeal-probability-if-wrong params)
            (:slashing-detection-probability params)))
        
        profits-honest (map :profit-honest results)
        profits-malice (map :profit-malice results)
        mean-honest (mean profits-honest)
        mean-malice (mean profits-malice)
        sorted-honest (sort profits-honest)
        sorted-malice (sort profits-malice)
        
        ; Phase B: Escalation metrics
        appeal-count (count (filter :appeal-triggered? results))
        escalation-count (count (filter :escalated? results))
        l2-count (count (filter #(= (:escalation-level %) 2) results))]
    
    {:n-trials n-trials
     :strategy (:strategy params :honest)
     
     ; Honest profit statistics
     :honest-mean (double mean-honest)
     :honest-std (double (std-dev profits-honest mean-honest))
     :honest-min (apply min profits-honest)
     :honest-max (apply max profits-honest)
     :honest-p25 (quantile sorted-honest 0.25)
     :honest-p50 (quantile sorted-honest 0.50)
     :honest-p75 (quantile sorted-honest 0.75)
     
     ; Malice profit statistics
     :malice-mean (double mean-malice)
     :malice-std (double (std-dev profits-malice mean-malice))
     :malice-min (apply min profits-malice)
     :malice-max (apply max profits-malice)
     :malice-p25 (quantile sorted-malice 0.25)
     :malice-p50 (quantile sorted-malice 0.50)
     :malice-p75 (quantile sorted-malice 0.75)
     
     ; Comparative statistics
     :honest-wins (count (filter #(> % 0) (map - profits-honest profits-malice)))
     :dominance-ratio (if (zero? mean-malice) Double/POSITIVE_INFINITY
                        (double (/ mean-honest mean-malice)))
     
     ; Phase B: Escalation statistics
     :appeal-rate (double (/ appeal-count n-trials))
     :escalation-rate (double (/ escalation-count n-trials))
     :l2-escalation-rate (double (/ l2-count n-trials))}))
