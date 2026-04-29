(ns resolver-sim.sim.trial-router
  "TrialRouter — protocol and implementations for assigning batch trials to
   individual resolvers in a multi-epoch simulation.

   The router answers the question: given N trial results and M resolvers,
   which resolver handled which trials?

   ## Conservation guarantee
   Every router implementation must satisfy — for each pool (honest / strategic):
     (sum :profit attributed to all resolvers) == (sum :profit in trial pool)
     (sum :trials attributed) == (count trial pool)
     (sum :slashed attributed) == (count (filter :slashed? trial pool))

   These are verified by `assert-conservation!` below.

   ## Routing modes

   | Mode                 | Purpose                                          |
   |----------------------|--------------------------------------------------|
   | :uniform-random      | baseline — seeded shuffle then round-robin       |
   | :capacity-weighted   | realistic load (future)                          |
   | :reputation-weighted | rich-get-richer dynamics (future)                |
   | :adversarial-routing | targeted slow-drip / ring pressure (future)      |

   Only :uniform-random is implemented in this branch. The interface is defined
   so the others can be added without changing multi_epoch.clj.

   Layering: sim/* only. No db/*, io/* imports."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol TrialRouter
  "Assigns a pool of per-trial results to a set of resolver IDs.

   A 'pool' is a vector of trial-result maps from one strategy perspective
   (e.g. all :profit-honest values for the honest pool, or all :profit-malice
   values for the strategic pool).

   route returns {resolver-id → resolver-epoch-result} where every resolver-id
   in resolver-ids appears as a key, even resolvers that received zero trials."

  (route [router resolver-ids trial-pool rng]
    "Assign trial-pool entries to resolver-ids.

     resolver-ids — collection of resolver IDs to assign to
     trial-pool   — vector of maps, each with at minimum:
                      :profit    numeric
                      :slashed?  bool
                      :verdicts  int (count of verdicts in this trial, default 1)
                      :correct   int (count of correct verdicts, default 0)
                      :appealed  bool
                      :escalated bool
     rng          — seeded SplittableRandom; must be the sole source of randomness

     Returns {resolver-id → {:trials N :profit P :slashed S :verdicts V
                              :correct C :appealed A :escalated E}}
     where every resolver-id key is present (N=0 for resolvers with no trials).")

  (routing-mode [router]
    "Return a keyword identifying this router implementation.
     Canonical values: :uniform-random :capacity-weighted :reputation-weighted
                       :adversarial-routing"))

;; ---------------------------------------------------------------------------
;; Seeded shuffle (Fisher-Yates)
;; ---------------------------------------------------------------------------

(defn- seeded-shuffle
  "Perform a Fisher-Yates shuffle of coll using seeded rng.
   Returns a new vector. Mutates rng state (as designed)."
  [coll ^java.util.SplittableRandom rng]
  (let [arr (object-array coll)
        n   (alength arr)]
    (loop [i (dec n)]
      (when (> i 0)
        (let [j   (rng/next-int rng (inc i))
              tmp (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp)
          (recur (dec i)))))
    (vec arr)))

;; ---------------------------------------------------------------------------
;; Aggregate a list of trial results into a single resolver-epoch-result
;; ---------------------------------------------------------------------------

(defn- aggregate-trials
  "Fold a (possibly empty) vector of trial-result maps into one
   resolver-epoch-result map."
  [trial-results]
  (if (empty? trial-results)
    {:trials 0 :profit 0.0 :slashed 0 :verdicts 0 :correct 0 :appealed 0 :escalated 0}
    (reduce (fn [acc t]
              (-> acc
                  (update :trials    inc)
                  (update :profit    + (:profit t 0.0))
                  (update :slashed   + (if (:slashed? t false) 1 0))
                  (update :verdicts  + (get t :verdicts 1))
                  (update :correct   + (get t :correct 0))
                  (update :appealed  + (if (:appeal-triggered? t false) 1 0))
                  (update :escalated + (if (:escalated? t false) 1 0))))
            {:trials 0 :profit 0.0 :slashed 0 :verdicts 0 :correct 0 :appealed 0 :escalated 0}
            trial-results)))

;; ---------------------------------------------------------------------------
;; Conservation check
;; ---------------------------------------------------------------------------

(defn attribution-conserved?
  "True when attribution sums match aggregate totals from the trial pool.

   Returns {:ok? bool :violations [...]} for use in assertions and tests."
  [attribution trial-pool]
  (let [pool-profit   (reduce + 0.0 (map #(double (:profit % 0.0)) trial-pool))
        pool-trials   (count trial-pool)
        pool-slashed  (count (filter :slashed? trial-pool))

        attr-profit   (reduce + 0.0 (map #(double (:profit %)) (vals attribution)))
        attr-trials   (reduce + 0 (map :trials (vals attribution)))
        attr-slashed  (reduce + 0 (map :slashed (vals attribution)))

        eps           1e-6
        violations    (cond-> []
                        (> (Math/abs (- attr-profit pool-profit)) eps)
                        (conj {:check :profit-sum
                               :expected pool-profit :got attr-profit
                               :delta (- attr-profit pool-profit)})

                        (not= attr-trials pool-trials)
                        (conj {:check :trial-count
                               :expected pool-trials :got attr-trials})

                        (not= attr-slashed pool-slashed)
                        (conj {:check :slash-count
                               :expected pool-slashed :got attr-slashed}))]
    {:ok? (empty? violations) :violations violations}))

(defn assert-conservation!
  "Throw if attribution does not conserve the trial pool totals."
  [attribution trial-pool label]
  (let [{:keys [ok? violations]} (attribution-conserved? attribution trial-pool)]
    (when-not ok?
      (throw (ex-info (str "Attribution conservation failure: " label)
                      {:violations violations})))))

;; ---------------------------------------------------------------------------
;; UniformRandomRouter
;; ---------------------------------------------------------------------------

(deftype UniformRandomRouter []
  TrialRouter

  (route [_ resolver-ids trial-pool rng]
    (let [ids-vec (vec resolver-ids)
          n-ids   (count ids-vec)]
      (if (zero? n-ids)
        {}
        (let [;; Shuffle trials with seeded RNG then assign round-robin.
              ;; Round-robin over a shuffled list guarantees:
              ;;   - every trial appears in exactly one resolver's bucket
              ;;   - load difference is at most 1 trial between any two resolvers
              ;;   - assignment is unpredictable without knowing the RNG state
              shuffled (seeded-shuffle trial-pool rng)
              ;; Group by (trial-index mod n-resolvers)
              grouped  (reduce (fn [acc [idx trial]]
                                 (let [rid (nth ids-vec (mod idx n-ids))]
                                   (update acc rid (fnil conj []) trial)))
                               (zipmap ids-vec (repeat []))
                               (map-indexed vector shuffled))]
          ;; Aggregate each resolver's trial list into a single result
          (reduce-kv (fn [acc rid trials]
                       (assoc acc rid (aggregate-trials trials)))
                     {}
                     grouped)))))

  (routing-mode [_] :uniform-random))

(def uniform-random
  "Shared UniformRandomRouter instance — the default for multi-epoch runs."
  (UniformRandomRouter.))

;; ---------------------------------------------------------------------------
;; Two-pool routing helper
;; ---------------------------------------------------------------------------

(defn route-epoch
  "Route one epoch's trial results to honest and strategic resolver pools.

   honest-ids        — seq of resolver IDs with :honest strategy
   strategic-ids     — seq of resolver IDs with non-honest strategies
   honest-trials     — vector of per-trial results run with strategy=:honest
   strategic-trials  — vector of per-trial results run with strategy=:malicious
                       (may equal honest-trials if only one batch is run)
   router            — TrialRouter implementation
   rng               — seeded RNG (will be split into two independent streams)

   Returns {:honest-attribution    {id → resolver-epoch-result}
            :strategic-attribution {id → resolver-epoch-result}}

   Conservation guarantee (checked internally via assert-conservation!):
     sum(honest profits)    == sum(:profit-honest honest-trials)
     sum(strategic profits) == sum(:profit-malice strategic-trials)"
  [honest-ids strategic-ids honest-trials strategic-trials router rng]
  (let [[rng-h rng-s] (rng/split-rng rng)

        honest-pool
        (mapv (fn [t] {:profit            (:profit-honest t 0.0)
                       :slashed?          false
                       :verdicts          1
                       :correct           (if (:dispute-correct? t false) 1 0)
                       :appeal-triggered? (:appeal-triggered? t false)
                       :escalated?        (:escalated? t false)})
              honest-trials)

        strategic-pool
        (mapv (fn [t] {:profit            (:profit-malice t 0.0)
                       :slashed?          (:slashed? t false)
                       :verdicts          1
                       :correct           0
                       :appeal-triggered? (:appeal-triggered? t false)
                       :escalated?        (:escalated? t false)})
              strategic-trials)

        honest-attr    (route router honest-ids    honest-pool    rng-h)
        strategic-attr (route router strategic-ids strategic-pool rng-s)]

    (assert-conservation! honest-attr    honest-pool    "honest pool")
    (assert-conservation! strategic-attr strategic-pool "strategic pool")

    {:honest-attribution    honest-attr
     :strategic-attribution strategic-attr}))