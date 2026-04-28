(ns resolver-sim.sim.multi-epoch
  "Multi-epoch reputation simulation (Phase J).
   Runs 10+ epochs with per-resolver tracking to address critical gaps:
   - Gap #1: Sybil resistance (reputation accumulation)
   - Gap #2: Governance failure (detection decay scenarios)
   - Gap #3: Multi-year dynamics (proof of stability)

   Output: Per-resolver history + aggregated statistics + equity trajectories.

   The :equity-trajectories key in the return value is a map
   {resolver-id → [profit@epoch1 profit@epoch2 ...]} built by
   resolver-sim.sim.trajectory/build-equity-trajectories."
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.sim.reputation :as rep]
            [resolver-sim.sim.trajectory :as trajectory]
            [resolver-sim.stochastic.rng :as rng]
            [clojure.set]))

(defn apply-detection-decay
  "Apply detection decay for governance failure scenarios.
   If :detection-decay-rate is set, multiply detection by (1 - decay-rate) each epoch.
   If :detection-failure-epoch is set, drop detection to 0 at that epoch.
   Returns: Updated params with decayed detection probabilities"
  [params epoch]
  (let [decay-rate (:detection-decay-rate params 0.0)
        failure-epoch (:detection-failure-epoch params nil)
        
        ; Check if we should fail detection
        should-fail? (and failure-epoch (>= epoch failure-epoch))
        
        ; Apply decay if enabled
        decay-multiplier (if should-fail?
                          0.0
                          (Math/pow (- 1.0 decay-rate) (dec epoch)))
        
        decayed-params
        (-> params
            (assoc :slashing-detection-probability
                   (* (:slashing-detection-probability params 0.1) decay-multiplier))
            (assoc :fraud-detection-probability
                   (* (:fraud-detection-probability params 0.0) decay-multiplier))
            (assoc :reversal-detection-probability
                   (* (:reversal-detection-probability params 0.0) decay-multiplier)))]
    
    decayed-params))

(defn run-single-epoch
  "Run one epoch (N trials) and return both batch stats and per-resolver breakdown.
   
   Args:
     rng: RNG state
     epoch: Epoch number (1-based)
     resolver-histories: Map of resolver-id -> resolver-state
     n-trials: Trials to run this epoch
     params: Simulation parameters
   
   Returns: {:epoch-summary {...}, :updated-histories {...}}"
  [rng epoch resolver-histories n-trials params]
  (let [; Apply decay for this epoch
        decayed-params (apply-detection-decay params epoch)
        
        ; Run batch simulation for this epoch
        batch-result (batch/run-batch rng n-trials decayed-params)
        
        ; For now, aggregate results across resolvers
        ; TODO: Extend batch.clj to return per-resolver breakdown
        ; For MVP, we'll infer resolver performance from strategy results
        
        epoch-summary
        {:epoch epoch
         :n-trials n-trials
         :honest-mean-profit (:honest-mean batch-result)
         :malice-mean-profit (:malice-mean batch-result)
         :dominance-ratio (:dominance-ratio batch-result)
         :appeal-rate (:appeal-rate batch-result)
         :slash-rate (:slash-rate batch-result)
         :fraud-slashed-count (:fraud-slashed-count batch-result 0)
         :reversal-slashed-count (:reversal-slashed-count batch-result 0)
         :timeout-slashed-count (:timeout-slashed-count batch-result 0)
         :detection-rate (:slashing-detection-probability decayed-params)}
        
        ; Update resolver histories based on batch results
        ; Strategy: Distribute batch profit proportionally to resolvers
        ; NOTE: This is simplified attribution. Ideal would require per-resolver tracking from batch.clj
        updated-histories
        (let [honest-mean (:honest-mean batch-result)
              malice-mean (:malice-mean batch-result)
              honest-count (count (filter #(= :honest (:strategy (val %))) resolver-histories))
              malice-count (count (filter #(#{:malicious :lazy :collusive} (:strategy (val %))) resolver-histories))]
          
          (reduce-kv (fn [acc id resolver]
                       (let [strategy (:strategy resolver)
                             is-honest? (= strategy :honest)
                             ; Distribute batch profit evenly across resolvers of same strategy
                             ; Each resolver gets a fair share of the epoch's profit
                             resolver-profit (if is-honest?
                                             (if (> honest-count 0) (/ honest-mean honest-count) 0)
                                             (if (> malice-count 0) (/ malice-mean malice-count) 0))
                             ; Simple slashing: if resolver is malicious and batch has slashed, mark as slashed
                             was-slashed? (and (not is-honest?)
                                              (> (:slash-rate batch-result 0) 0)
                                              (< (rand) (:slash-rate batch-result 0.1)))
                             
                             updated (rep/update-resolver-history
                                     resolver
                                     resolver-profit
                                     1  ; 1 verdict per epoch (simplified attribution)
                                     (if is-honest? 1 0)
                                     was-slashed?
                                     epoch)]
                         
                         (assoc acc id updated)))
                     {} resolver-histories))]
    
    ; Apply population decay (exits/new entries)
    (let [decayed-histories (rep/apply-epoch-decay updated-histories epoch params)]
      {:epoch-summary epoch-summary
       :updated-histories decayed-histories})))

(defn run-multi-epoch
  "Run N epochs with reputation tracking.
   
   Args:
     rng: RNG state
     n-epochs: Number of epochs (default 10)
     n-trials-per-epoch: Trials per epoch (default 500)
     params: Simulation parameters
   
   Returns: {:epoch-results [...], :resolver-histories {...}, :aggregated-stats {...}}"
  [rng n-epochs n-trials-per-epoch params]
  (let [n-resolvers (get params :n-resolvers 100)
        strategy-mix (or (:strategy-mix params)
                        {:honest 0.50 :lazy 0.15 :malicious 0.25 :collusive 0.10})]
    
    (println (format "\n🔁 Running Phase J: Multi-Epoch Reputation Simulation"))
    (println (format "   Epochs: %d" n-epochs))
    (println (format "   Trials per epoch: %d" n-trials-per-epoch))
    (println (format "   Initial resolvers: %d" n-resolvers))
    (println (format "   Strategy mix: %s" strategy-mix))
    (println "")
    
    ; Initialize resolver cohort
    (let [initial-histories (rep/initialize-resolvers n-resolvers strategy-mix)
          
          ; Run epoch loop — accumulate epoch summaries AND per-epoch profit snapshots.
          ; A snapshot is {resolver-id → total-profit-at-end-of-epoch} and feeds
          ; trajectory/build-equity-trajectories after the loop.
          result-accumulator
          (reduce (fn [acc epoch-num]
                    (let [[rng-1 rng-2] (rng/split-rng (:rng acc))
                          prev-histories (:histories acc initial-histories)
                          {:keys [epoch-summary updated-histories]}
                          (run-single-epoch rng-1 epoch-num prev-histories n-trials-per-epoch params)

                          ; Snapshot cumulative profit for every resolver at this epoch end.
                          profit-snapshot
                          (reduce-kv (fn [m id r] (assoc m id (rep/cumulative-profit r)))
                                     {} updated-histories)

                          _ (println (format "   Epoch %d: honest=%.0f, malice=%.0f, dominance=%.1f×"
                                             epoch-num
                                             (:honest-mean-profit epoch-summary)
                                             (:malice-mean-profit epoch-summary)
                                             (:dominance-ratio epoch-summary)))]

                      (assoc acc
                             :rng             rng-2
                             :epochs          (cons epoch-summary (:epochs acc []))
                             :histories       updated-histories
                             :epoch-snapshots (conj (:epoch-snapshots acc []) profit-snapshot))))
                  {:rng rng :epochs [] :histories initial-histories :epoch-snapshots []}
                  (range 1 (inc n-epochs)))

          epoch-results    (reverse (:epochs result-accumulator []))
          epoch-snapshots  (:epoch-snapshots result-accumulator [])
          final-histories  (:histories result-accumulator initial-histories)
          resolver-ids     (keys final-histories)

          ; Aggregate final statistics
          final-stats
          (let [histories        final-histories
                honest-resolvers (filter #(= :honest (:strategy (val %))) histories)
                malice-resolvers (filter #(#{:malicious :lazy :collusive} (:strategy (val %))) histories)

                honest-profits   (map #(rep/cumulative-profit (val %)) honest-resolvers)
                malice-profits   (map #(rep/cumulative-profit (val %)) malice-resolvers)

                honest-win-rates (map #(rep/win-rate (val %)) honest-resolvers)
                malice-win-rates (map #(rep/win-rate (val %)) malice-resolvers)

                initial-ids      (set (keys initial-histories))
                final-ids        (set (keys histories))
                total-exits      (count (clojure.set/difference initial-ids final-ids))]

            {:final-resolver-count       (count histories)
             :total-resolver-exits       total-exits
             :honest-final-count         (count honest-resolvers)
             :malice-final-count         (count malice-resolvers)
             :honest-cumulative-profit   (double (apply + (map #(rep/cumulative-profit (val %)) honest-resolvers)))
             :malice-cumulative-profit   (double (apply + (map #(rep/cumulative-profit (val %)) malice-resolvers)))
             :honest-avg-win-rate        (if (empty? honest-win-rates) 0.0
                                           (double (/ (apply + honest-win-rates) (count honest-win-rates))))
             :malice-avg-win-rate        (if (empty? malice-win-rates) 0.0
                                           (double (/ (apply + malice-win-rates) (count malice-win-rates))))
             :honest-exit-rate           (if (empty? honest-resolvers) 0.0
                                           (double (/ total-exits n-resolvers)))
             :malice-exit-rate           (if (empty? malice-resolvers) 0.0
                                           (double (/ total-exits
                                                      (+ (count malice-resolvers)
                                                         (count (filter #(#{:malicious :lazy :collusive}
                                                                           (:strategy (val %)))
                                                                        initial-histories))))))})

          ; Build trajectory data using the shared trajectory namespace.
          equity-trajectories          (trajectory/build-equity-trajectories epoch-snapshots resolver-ids)
          strategy-spread-trajectories (trajectory/strategy-spread-trajectory final-histories epoch-snapshots)

          result
          {:scenario-id        (:scenario-id params "phase-j-unnamed")
           :n-epochs           n-epochs
           :n-trials-per-epoch n-trials-per-epoch
           :initial-resolver-count n-resolvers
           :epoch-results      (or epoch-results [])
           :resolver-histories final-histories
           :aggregated-stats   final-stats
           ; --- trajectory output (T1b / T1c) ---
           :equity-trajectories          equity-trajectories
           :strategy-spread-trajectories strategy-spread-trajectories
           :trajectory/meta              {:type        :trajectory/equity
                                          :epoch-count n-epochs
                                          :unit        :profit}}]
      
      (println "")
      (println (format "✓ Phase J complete. Final state:"))
      (println (format "   Resolvers exited: %d" (:total-resolver-exits final-stats)))
      (println (format "   Honest cumulative: %.0f" (:honest-cumulative-profit final-stats)))
      (println (format "   Malice cumulative: %.0f" (:malice-cumulative-profit final-stats)))
      (println (format "   Win rate - honest: %.1f%%" (* 100 (:honest-avg-win-rate final-stats))))
      (println (format "   Win rate - malice: %.1f%%" (* 100 (:malice-avg-win-rate final-stats))))
      (println "")
      
      result)))
