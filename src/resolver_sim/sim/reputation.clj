(ns resolver-sim.sim.reputation
  "Per-resolver reputation tracking for multi-epoch simulations."
  (:require [clojure.string :as str]))

(defn initialize-resolvers
  "Create initial cohort of resolvers from strategy mix.
   Returns map: {resolver-id -> resolver-state}"
  [n-resolvers strategy-mix]
  (let [honest-count (int (* n-resolvers (:honest strategy-mix 0)))
        lazy-count (int (* n-resolvers (:lazy strategy-mix 0)))
        malicious-count (int (* n-resolvers (:malicious strategy-mix 0)))
        collusive-count (- n-resolvers honest-count lazy-count malicious-count)]
    
    (reduce (fn [acc [strategy count]]
              (reduce (fn [map _]
                        (let [id (str (gensym (name strategy)))]
                          (assoc map id
                            {:resolver-id id
                             :strategy strategy
                             :status :active
                             :total-profit 0.0
                             :total-fees-earned 0.0
                             :total-slashing-loss 0.0
                             :total-verdicts 0
                             :total-correct 0
                             :total-slashed 0
                             :exit-probability 0.0
                             :epoch-history {}})))
                        acc (range count)))
            {}
            [[:honest honest-count]
             [:lazy lazy-count]
             [:malicious malicious-count]
             [:collusive collusive-count]])))

(defn update-resolver-history
  "Update resolver state after a single dispute outcome.
   Returns: Updated resolver record"
  [resolver profit verdicts-this-trial correct-this-trial slashed? epoch]
  (let [old-history (:epoch-history resolver {})
        epoch-key (keyword (str "epoch-" epoch))
        epoch-data (get old-history epoch-key {:profit 0.0 :verdicts 0 :slashed? false})
        
        new-epoch-data
        {:profit (+ (:profit epoch-data 0.0) profit)
         :verdicts (+ (:verdicts epoch-data 0) verdicts-this-trial)
         :correct (+ (:correct epoch-data 0) correct-this-trial)
         :slashed? (or (:slashed? epoch-data false) slashed?)
         :epoch epoch}]
    
    (assoc resolver
      :total-profit (+ (:total-profit resolver) profit)
      :total-fees-earned (+ (:total-fees-earned resolver) (max 0.0 profit))
      :total-slashing-loss (+ (:total-slashing-loss resolver) (if slashed? (abs profit) 0.0))
      :total-verdicts (+ (:total-verdicts resolver) verdicts-this-trial)
      :total-correct (+ (:total-correct resolver) correct-this-trial)
      :total-slashed (+ (:total-slashed resolver) (if slashed? 1 0))
      :epoch-history (assoc old-history epoch-key new-epoch-data))))

(defn calculate-exit-probability
  "Based on cumulative losses and slashing, probability resolver exits.
   Returns: Double between 0 and 1"
  [resolver epoch params]
  (let [total-loss (:total-slashing-loss resolver 0.0)
        times-slashed (:total-slashed resolver 0)
        total-profit (:total-profit resolver 0.0)
        strategy (:strategy resolver)
        
        ; Base exit probability by strategy
        base-prob (case strategy
                    :honest 0.001      ; Honest almost never exit (profit positive)
                    :lazy 0.01         ; Lazy occasionally exit (lower profits)
                    :malicious 0.05    ; Malicious frequently exit (slashed & unprofitable)
                    :collusive 0.03    ; Collusive sometimes exit (detected)
                    0.01)
        
        ; Increase probability if slashed recently
        slashing-penalty (if (> times-slashed 0) (* 0.02 times-slashed) 0.0)
        
        ; Decrease probability if profitable
        profitability-bonus (if (> total-profit 0) (- 0.01) 0.0)]
    
    (max 0.0 (min 1.0 (+ base-prob slashing-penalty profitability-bonus)))))

(defn apply-epoch-decay
  "After each epoch, remove exited resolvers, add new ones to maintain population.
   Returns: Updated resolver-histories map"
  [resolver-histories epoch-num params]
  (let [n-total (count resolver-histories)
        
        ; Calculate exits
        active-resolvers
        (reduce-kv (fn [acc id resolver]
                     (let [exit-prob (calculate-exit-probability resolver epoch-num params)]
                       (if (<= (rand) exit-prob)
                         acc  ; Remove this resolver
                         (assoc acc id (assoc resolver :status :active)))))
                   {} resolver-histories)
        
        n-remaining (count active-resolvers)
        n-new (- n-total n-remaining)  ; How many to add back
        
        ; Determine strategy mix for new entrants (assume honest is attractive)
        new-strategy-mix
        (if (> n-new 0)
          {:honest (/ n-new 2)
           :lazy (/ n-new 4)
           :malicious (/ n-new 8)
           :collusive (/ n-new 8)}
          {})]
    
    ; Add new resolvers to replace exited ones
    (if (> n-new 0)
      (let [new-resolvers (initialize-resolvers n-new new-strategy-mix)]
        (merge active-resolvers new-resolvers))
      active-resolvers)))

(defn win-rate
  "Calculate per-resolver win rate.
   Returns: Double between 0 and 1"
  [resolver]
  (let [total (:total-verdicts resolver 0)
        correct (:total-correct resolver 0)]
    (if (> total 0)
      (double (/ correct total))
      0.0)))

(defn cumulative-profit
  "Get resolver's cumulative profit.
   Returns: Double"
  [resolver]
  (:total-profit resolver 0.0))

(defn resolver-status-summary
  "Get human-readable summary of resolver state.
   Returns: String"
  [resolver]
  (let [strategy (:strategy resolver)
        profit (cumulative-profit resolver)
        slashed (:total-slashed resolver 0)
        win-pct (* 100 (win-rate resolver))]
    (format "%s[%s] profit=%.0f, wins=%.0f%%, slashed=%d"
            (:resolver-id resolver) (name strategy) profit win-pct slashed)))
