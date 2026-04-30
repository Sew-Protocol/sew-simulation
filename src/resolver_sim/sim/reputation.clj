(ns resolver-sim.sim.reputation
  "Per-resolver reputation tracking for multi-epoch simulations."
  (:require [clojure.string :as str]
            [resolver-sim.stochastic.rng :as rng]))

(defn initialize-resolvers
  "Create initial cohort of resolvers from strategy mix.

   start-id — first numeric suffix for resolver IDs (default 0).
              Pass a non-zero value when adding new entrants to avoid
              ID collisions with existing resolvers.

   IDs are deterministic: \"<strategy>-<N>\" where N is sequential from start-id.
   No gensym — same arguments always produce the same ID set."
  ([n-resolvers strategy-mix] (initialize-resolvers n-resolvers strategy-mix 0))
  ([n-resolvers strategy-mix start-id]
   (let [honest-count    (int (* n-resolvers (:honest strategy-mix 0)))
         lazy-count      (int (* n-resolvers (:lazy strategy-mix 0)))
         malicious-count (int (* n-resolvers (:malicious strategy-mix 0)))
         collusive-count (- n-resolvers honest-count lazy-count malicious-count)
         groups          [[:honest    honest-count]
                          [:lazy      lazy-count]
                          [:malicious malicious-count]
                          [:collusive collusive-count]]]
     (first
      (reduce
       (fn [[m next-id] [strategy cnt]]
         [(reduce (fn [acc i]
                    (let [id (str (name strategy) "-" (+ next-id i))]
                      (assoc acc id
                             {:resolver-id         id
                              :strategy            strategy
                              :status              :active
                              :total-profit        0.0
                              :total-fees-earned   0.0
                              :total-slashing-loss 0.0
                              :total-trials        0
                              :total-verdicts      0
                              :total-correct       0
                              :total-slashed       0
                              :total-appealed      0
                              :total-escalated     0
                              :exit-probability    0.0
                              :epoch-history       {}})))
                  m (range cnt))
          (+ next-id cnt)])
       [{} start-id]
       groups)))))

(defn update-resolver-history
  "Update resolver state after one epoch's attributed trials.

   profit             — net profit this epoch (sum of attributed trial profits)
   verdicts           — verdicts rendered this epoch
   correct            — correct verdicts this epoch (0 for strategic resolvers)
   slashed?           — true if at least one slash event occurred this epoch
   epoch              — epoch number (1-based)
   trials             — number of trials attributed to this resolver (may be 0)
   appealed           — number of appeal events
   escalated          — number of escalation events

   Returns: Updated resolver record."
  [resolver profit verdicts correct slashed? epoch
   & {:keys [trials appealed escalated] :or {trials 0 appealed 0 escalated 0}}]
  (let [old-history (:epoch-history resolver {})
        epoch-key   (keyword (str "epoch-" epoch))
        new-epoch   {:profit    profit
                     :trials    trials
                     :verdicts  verdicts
                     :correct   correct
                     :slashed?  slashed?
                     :appealed  appealed
                     :escalated escalated
                     :epoch     epoch}]
    (assoc resolver
           :total-profit        (+ (:total-profit resolver 0.0) profit)
           :total-fees-earned   (+ (:total-fees-earned resolver 0.0) (max 0.0 profit))
           :total-slashing-loss (+ (:total-slashing-loss resolver 0.0) (if slashed? (- (min 0.0 profit)) 0.0))
           :total-trials        (+ (:total-trials resolver 0) trials)
           :total-verdicts      (+ (:total-verdicts resolver 0) verdicts)
           :total-correct       (+ (:total-correct resolver 0) correct)
           :total-slashed       (+ (:total-slashed resolver 0) (if slashed? 1 0))
           :total-appealed      (+ (:total-appealed resolver 0) appealed)
           :total-escalated     (+ (:total-escalated resolver 0) escalated)
           :epoch-history       (assoc old-history epoch-key new-epoch))))

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

   decay-rng  — seeded RNG for deterministic exit decisions.
   next-id    — starting numeric suffix for new entrant IDs (avoids ID collisions).
   Returns: {:histories updated-map :next-id N}"
  [resolver-histories epoch-num params decay-rng next-id]
  (let [n-total (count resolver-histories)
        active-resolvers
        (reduce-kv (fn [acc id resolver]
                     (let [exit-prob (calculate-exit-probability resolver epoch-num params)]
                       (if (<= (rng/next-double decay-rng) exit-prob)
                         acc
                         (assoc acc id (assoc resolver :status :active)))))
                   {} resolver-histories)
        n-new (- n-total (count active-resolvers))
        new-strategy-mix (when (pos? n-new)
                           {:honest    0.50
                            :lazy      0.25
                            :malicious 0.125
                            :collusive 0.125})]
    (if (pos? n-new)
      (let [new-resolvers (initialize-resolvers n-new new-strategy-mix next-id)]
        {:histories (merge active-resolvers new-resolvers)
         :next-id   (+ next-id n-new)})
      {:histories active-resolvers
       :next-id   next-id})))

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
