(ns resolver-sim.db.telemetry
  "Adapter between the contract-model runner and the eval-engine XTDB store.

   Converts run-trial / run-with-divergence-check output into the generic record
   format expected by resolver-sim.db.store and writes it to XTDB.

   Records are stored in the protocol-agnostic sim_trial_results table; all
   SEW-specific fields (strategy, profits, appeal flags, etc.) are serialised
   as a metrics_edn blob alongside the generic header fields.

   All write functions accept a datasource as their first argument.
   Passing nil is safe: writes become no-ops, enabling offline simulation
   runs and unit tests without a live XTDB instance.

   Typical usage:

     ;; With XTDB running:
     (def ds (evaluation.store/->datasource))
     (telemetry/record-trial! ds batch-id trial-id params result)

     ;; Without XTDB (default, tests):
     (telemetry/record-trial! nil batch-id trial-id params result)"
  (:require [resolver-sim.db.store :as ss])
  (:import [java.util Date UUID]))

;; ---------------------------------------------------------------------------
;; Conversion helpers (pure — no I/O)
;; ---------------------------------------------------------------------------

(defn- sim-date
  "Convert a simulated block-time long (unix seconds) to a java.util.Date.
   If block-time is nil, returns the current wall-clock time."
  [block-time]
  (if block-time
    (Date. (* ^long block-time 1000))
    (Date.)))

(defn trial->outcome-record
  "Convert a run-trial result (plus contextual metadata) into the generic map
   expected by resolver-sim.db.store/insert-trial-result!.

   Arguments:
     trial-id   — unique string identifier for this trial
     batch-id   — string or keyword identifying the simulation batch
     params     — the params map passed to run-trial
     result     — the map returned by run-trial or run-with-divergence-check

   The returned map has:
     Top-level generic fields: :id, :batch-id, :protocol-id, :outcome,
       :invariants-ok?, :divergence?, :params, :violations, :valid-from
     :metrics blob: all SEW-specific fields (strategy, profits, appeal flags,
       dispute-correct?, slashed?, cm-fee, cm-afa, diffs).

   When result is a run-with-divergence-check map (contains :contract),
   the :contract sub-map is used for trial fields and :divergence for diffs."
  [trial-id batch-id params result]
  (let [cm    (if (contains? result :contract) (:contract result) result)
        div   (get result :divergence {})
        btime (get params :block-time 1000)]
    {:id          trial-id
     :batch-id    batch-id
     :protocol-id "sew-v1"
     :outcome     (get cm :cm/final-state)
     :invariants-ok? (boolean (get cm :cm/invariants-ok? true))
     :divergence?    (boolean (get div :divergence?))
     :params      params
     :metrics     {:strategy          (get cm :strategy (get params :strategy :honest))
                   :dispute-correct?  (boolean (get cm :dispute-correct?))
                   :appeal-triggered? (boolean (get cm :appeal-triggered?))
                   :slashed?          (boolean (get cm :slashed?))
                   :profit-honest     (long (get cm :profit-honest 0))
                   :profit-malice     (long (get cm :profit-malice 0))
                   :cm-fee            (long (get cm :cm/fee 0))
                   :cm-afa            (long (get cm :cm/afa 0))
                   :diffs             (get div :diffs)}
     :violations  (get cm :cm/inv-violations)
     :valid-from  (sim-date btime)}))

(defn trial->event-records
  "Derive a sequence of sim_entity_events records from a run-trial result.

   Reconstructs the state timeline from the outcome fields. Each record
   has a unique :id derived from trial-id and the event step.

   Currently emits up to 3 events per trial:
     :sew/escrow-created  — always
     :sew/dispute-raised  — always (all adversarial trials raise a dispute)
     :sew/escrow-finalized — final state (released/refunded/resolved)"
  [trial-id params result]
  (let [cm     (if (contains? result :contract) (:contract result) result)
        btime  (long (get params :block-time 1000))
        fstate (get cm :cm/final-state :pending)
        mk     (fn [step etype estate t]
                 {:id           (str trial-id "-" (name step))
                  :trial-id     trial-id
                  :entity-id    "0"
                  :event-type   etype
                  :entity-state estate
                  :block-time   t
                  :valid-from   (sim-date t)})]
    [(mk :created  :sew/escrow-created  :pending    btime)
     (mk :disputed :sew/dispute-raised  :disputed   (+ btime 10))
     (mk :final    :sew/escrow-finalized fstate      (+ btime 200))]))

;; ---------------------------------------------------------------------------
;; Write functions (side-effecting — require XTDB datasource)
;; ---------------------------------------------------------------------------

(defn record-trial!
  "Write one trial outcome and its entity event sequence to XTDB.

   All writes are skipped when ds is nil.
   Returns the outcome record map (useful for chaining / inspection)."
  [ds batch-id trial-id params result]
  (let [outcome (trial->outcome-record trial-id batch-id params result)
        events  (trial->event-records  trial-id params result)]
    (ss/insert-trial-result! ds outcome)
    (doseq [ev events]
      (ss/insert-entity-event! ds ev))
    outcome))

(defn record-batch!
  "Write a collection of trial results to XTDB.

   trials — sequence of maps, each with keys:
     :trial-id  — unique string (auto-generated if absent)
     :params    — the params map for this trial
     :result    — the map returned by run-trial or run-with-divergence-check

   Returns a vector of outcome record maps.
   All writes are skipped when ds is nil."
  [ds batch-id trials]
  (mapv (fn [{:keys [trial-id params result]}]
          (let [tid (or trial-id (str (UUID/randomUUID)))]
            (record-trial! ds batch-id tid params result)))
        trials))

;; ---------------------------------------------------------------------------
;; Query helpers (delegate to sew-store)
;; ---------------------------------------------------------------------------

(defn batch-summary
  "Return summary statistics for a stored batch.

   Fetches all trial outcomes for batch-id from XTDB and computes
   aggregate statistics using resolver-sim.db.store/summarise-batch.

   Returns {} when ds is nil."
  [ds batch-id]
  (if (nil? ds)
    {}
    (-> (ss/sew-trial-outcomes ds {:batch-id batch-id})
        ss/summarise-batch)))
