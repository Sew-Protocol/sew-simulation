(ns resolver-sim.sim.trajectory
  "Shared trajectory types, schemas, and helpers for multi-agent audit phases.

   This namespace is a vocabulary boundary. It defines the canonical trajectory
   type keywords and provides pure helpers for constructing and querying
   trajectory data structures. It does not run simulations.

   Trajectory types:
     :trajectory/equity          — cumulative profit per resolver over epochs
     :trajectory/strategy-spread — honest vs. strategic equity gap per epoch
     :trajectory/displacement    — honest-resolver share eroded by attacker ring
     :trajectory/invariant-margin — invariant headroom over epochs (future)

   Layering: may be imported by sim/* only.
   model/*, contract_model/* must NOT import this namespace.")

;; ---------------------------------------------------------------------------
;; Canonical type vocabulary
;; ---------------------------------------------------------------------------

(def trajectory-types
  "Canonical set of trajectory type keywords used across all audit phases."
  #{:trajectory/equity
    :trajectory/strategy-spread
    :trajectory/displacement
    :trajectory/invariant-margin})

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- strategic-strategy? [strategy]
  (#{:malicious :lazy :collusive :ring} strategy))

(defn- mean-of [coll]
  (if (seq coll)
    (double (/ (reduce + coll) (count coll)))
    0.0))

;; ---------------------------------------------------------------------------
;; Equity trajectories
;; ---------------------------------------------------------------------------

(defn equity-trajectory
  "Extract per-epoch cumulative profit for one resolver from epoch-snapshots.

   epoch-snapshots — vector of maps {resolver-id → profit}, one per epoch in order.
   resolver-id     — the resolver to extract.

   Returns a vector of profit values, length = (count epoch-snapshots)."
  [epoch-snapshots resolver-id]
  (mapv #(get % resolver-id 0.0) epoch-snapshots))

(defn build-equity-trajectories
  "Build the full :equity-trajectories map from epoch-snapshots.

   epoch-snapshots — [{resolver-id → profit} ...].
   resolver-ids    — seq of all resolver ids to include.

   Returns {resolver-id → [profit@e1 profit@e2 ...]}."
  [epoch-snapshots resolver-ids]
  (reduce (fn [acc id]
            (assoc acc id (equity-trajectory epoch-snapshots id)))
          {}
          resolver-ids))

;; ---------------------------------------------------------------------------
;; Strategy-spread trajectory
;; ---------------------------------------------------------------------------

(defn strategy-spread-at-epoch
  "Compute honest vs. strategic equity spread at one epoch.

   resolver-histories — {resolver-id → resolver-state} (with :strategy key).
   epoch-snapshots    — [{resolver-id → profit} ...] indexed from 0.
   epoch-idx          — 0-based index into epoch-snapshots.

   Returns {:epoch :honest-count :strategic-count
            :honest-mean-equity :strategic-mean-equity :spread}."
  [resolver-histories epoch-snapshots epoch-idx]
  (let [snapshot     (nth epoch-snapshots epoch-idx nil)
        honest-ids   (keep (fn [[id r]] (when (= :honest (:strategy r)) id))
                           resolver-histories)
        strat-ids    (keep (fn [[id r]] (when (strategic-strategy? (:strategy r)) id))
                           resolver-histories)
        mean-equity  (fn [ids]
                       (mean-of (map #(get snapshot % 0.0) ids)))
        h-mean       (mean-equity honest-ids)
        s-mean       (mean-equity strat-ids)]
    {:epoch                 (inc epoch-idx)
     :honest-count          (count honest-ids)
     :strategic-count       (count strat-ids)
     :honest-mean-equity    h-mean
     :strategic-mean-equity s-mean
     :spread                (- s-mean h-mean)}))

(defn strategy-spread-trajectory
  "Compute strategy-spread at every epoch.

   Returns vector of strategy-spread maps (one per epoch)."
  [resolver-histories epoch-snapshots]
  (mapv #(strategy-spread-at-epoch resolver-histories epoch-snapshots %)
        (range (count epoch-snapshots))))

;; ---------------------------------------------------------------------------
;; Displacement trajectory
;; ---------------------------------------------------------------------------

(defn displacement-trajectory
  "Build a displacement trajectory from per-epoch resolver counts.

   epoch-resolver-counts — seq of {:epoch :honest-active :ring-active}.

   Returns [{:epoch :honest-active :ring-active :honest-share} ...]
   showing whether honest-resolver displacement is gradual, sudden, or reversible."
  [epoch-resolver-counts]
  (mapv (fn [{:keys [epoch honest-active ring-active]}]
          (let [total (+ (or honest-active 0) (or ring-active 0))]
            {:epoch         epoch
             :honest-active (or honest-active 0)
             :ring-active   (or ring-active 0)
             :honest-share  (if (pos? total)
                              (double (/ honest-active total))
                              0.0)}))
        epoch-resolver-counts))

;; ---------------------------------------------------------------------------
;; Statistical helpers
;; ---------------------------------------------------------------------------

(defn p95
  "Compute the 95th percentile of a numeric collection. Returns 0.0 if empty."
  [coll]
  (if (empty? coll)
    0.0
    (let [sorted (sort coll)
          idx    (int (Math/floor (* 0.95 (dec (count sorted)))))]
      (double (nth sorted idx)))))

(defn divergence-ratio
  "Compute strategic-to-honest equity ratio. Returns nil when honest-mean is zero."
  [honest-mean strategic-mean]
  (when (pos? honest-mean)
    (double (/ strategic-mean honest-mean))))
