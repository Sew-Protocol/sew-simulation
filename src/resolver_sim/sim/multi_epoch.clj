(ns resolver-sim.sim.multi-epoch
  "Multi-epoch reputation simulation (Phase J).
   Runs 10+ epochs with per-resolver tracking to address critical gaps:
   - Gap #1: Sybil resistance (reputation accumulation)
   - Gap #2: Governance failure (detection decay scenarios)
   - Gap #3: Multi-year dynamics (proof of stability)

   Output: Per-resolver history + aggregated statistics + equity trajectories.

   The :equity-trajectories key in the return value is a map
   {resolver-id → [profit@epoch1 profit@epoch2 ...]} built by
   resolver-sim.sim.trajectory/build-equity-trajectories.

   Attribution model: each epoch, batch trials are routed to individual resolvers
   via a TrialRouter (default: :uniform-random). Conservation invariants are
   checked inside run-single-epoch — any routing bug that changes the economics
   throws before it can corrupt trajectory data."
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.sim.reputation :as rep]
            [resolver-sim.sim.trial-router :as router]
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
  "Run one epoch (N trials) and return batch stats + per-resolver histories.

   Uses run-batch-with-attribution to get per-trial results, then routes them
   to individual resolvers via the trial router. Conservation invariants are
   checked inside route-epoch — any attribution bug throws immediately.

   Args:
     rng               — seeded SplittableRandom (mutated in place)
     epoch             — epoch number (1-based)
     resolver-histories — {resolver-id → resolver-state}
     n-trials          — trials to run this epoch
     params            — simulation parameters
     trial-router      — TrialRouter implementation (default: uniform-random)

   Returns: {:epoch-summary {...} :updated-histories {...}}"
  ([rng epoch resolver-histories n-trials params]
   (run-single-epoch rng epoch resolver-histories n-trials params router/uniform-random))
  ([rng epoch resolver-histories n-trials params trial-router]
   (let [decayed-params (apply-detection-decay params epoch)

         ;; Run batch — get both aggregate stats and per-trial results
         {:keys [aggregate trials]} (batch/run-batch-with-attribution rng n-trials decayed-params)

         epoch-summary
         {:epoch                  epoch
          :n-trials               n-trials
          :honest-mean-profit     (:honest-mean aggregate)
          :malice-mean-profit     (:malice-mean aggregate)
          :dominance-ratio        (:dominance-ratio aggregate)
          :appeal-rate            (:appeal-rate aggregate)
          :slash-rate             (:slash-rate aggregate)
          :fraud-slashed-count    (:fraud-slashed-count aggregate 0)
          :reversal-slashed-count (:reversal-slashed-count aggregate 0)
          :timeout-slashed-count  (:timeout-slashed-count aggregate 0)
          :detection-rate         (:slashing-detection-probability decayed-params)
          :routing-mode           (router/routing-mode trial-router)}

         ;; Partition resolvers by pool
         honest-ids     (vec (keep (fn [[id r]] (when (= :honest (:strategy r)) id))
                                   resolver-histories))
         strategic-ids  (vec (keep (fn [[id r]] (when (not= :honest (:strategy r)) id))
                                   resolver-histories))

         ;; Route trials to resolvers — conservation checked inside route-epoch
         [route-rng decay-rng] (rng/split-rng rng)
         {:keys [honest-attribution strategic-attribution]}
         (router/route-epoch honest-ids strategic-ids trials trial-router route-rng)

         ;; Merge both attribution maps and update histories
         all-attribution (merge honest-attribution strategic-attribution)

         updated-histories
         (reduce-kv
          (fn [acc id resolver]
            (let [attr      (get all-attribution id
                                 {:trials 0 :profit 0.0 :slashed 0
                                  :verdicts 0 :correct 0 :appealed 0 :escalated 0})
                  is-honest? (= :honest (:strategy resolver))]
              (assoc acc id
                     (rep/update-resolver-history
                      resolver
                      (:profit attr 0.0)
                      (:verdicts attr 0)
                      (if is-honest? (:verdicts attr 0) 0)
                      (pos? (:slashed attr 0))
                      epoch))))
          {}
          resolver-histories)]

     ;; Apply population decay with seeded RNG
     (let [decayed-histories (rep/apply-epoch-decay updated-histories epoch params decay-rng)]
       {:epoch-summary     epoch-summary
        :updated-histories decayed-histories}))))

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
