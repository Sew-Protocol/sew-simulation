(ns resolver-sim.contract-model.store
  "XTDB persistence for SEW protocol simulation outcomes.

   Two tables (auto-created by XTDB on first INSERT):

     sew_trial_outcomes — one row per simulation trial
       Columns: trial_id, batch_id, strategy, final_state, dispute_correct,
                appeal_triggered, slashed, profit_honest, profit_malice,
                cm_fee, cm_afa, invariants_ok, divergence_detected,
                params_edn, violations_edn, diffs_edn, _valid_from

     sew_escrow_events — one row per escrow state-transition event within a trial
       Columns: trial_id, workflow_id, event_type, escrow_state,
                block_time, _valid_from

   Valid-time semantics:
     _valid_from = simulated block timestamp (as java.util.Date).
     Queries with FOR VALID_TIME AS OF reproduce the escrow state at any
     point in the simulated chain timeline.

   Datasource:
     Use evaluation.xtdb/->datasource to obtain a connection to the shared
     XTDB pgwire endpoint.  Pass nil as the datasource to skip all writes
     (useful in tests and offline simulation runs)."
  (:require [next.jdbc       :as jdbc]
            [evaluation.xtdb :as xtdb]))

;; ---------------------------------------------------------------------------
;; Schema helpers
;; ---------------------------------------------------------------------------

(defn truncate!
  "Delete all SEW simulation rows. Ignores errors for tables that have
   never been written to (XTDB raises an error on DELETE from a non-existent
   table)."
  [ds]
  (doseq [tbl ["sew_trial_outcomes" "sew_escrow_events"]]
    (try
      (jdbc/execute! ds [(str "DELETE FROM " tbl)])
      (catch Exception _))))

;; ---------------------------------------------------------------------------
;; sew_trial_outcomes — writes
;; ---------------------------------------------------------------------------

(defn insert-sew-trial-outcome!
  "Insert an aggregate trial outcome row.

   Required keys:
     :id            — unique trial id string (UUID recommended)
     :batch-id      — identifies the simulation batch (string or keyword)
     :strategy      — :honest | :lazy | :malicious | :collusive
     :final-state   — :released | :refunded | :resolved
     :profit-honest — long (protocol fee captured by honest resolver)
     :profit-malice — long (profit to malicious resolver, negative if slashed)
     :valid-from    — java.util.Date corresponding to simulated block time

   Optional keys (all default to nil/false):
     :dispute-correct?   — boolean
     :appeal-triggered?  — boolean
     :slashed?           — boolean
     :cm-fee             — long (protocol fee in wei)
     :cm-afa             — long (amount-after-fee = principal in wei)
     :invariants-ok?     — boolean (were all CM invariants satisfied?)
     :divergence?        — boolean (CM outcome diverged from idealised model)
     :params             — map (full trial params; stored as EDN)
     :violations         — map (invariant violations; stored as EDN)
     :diffs              — vector (divergence diffs; stored as EDN)

   No-op when ds is nil."
  [ds {:keys [id batch-id strategy final-state
              dispute-correct? appeal-triggered? slashed?
              profit-honest profit-malice
              cm-fee cm-afa invariants-ok? divergence?
              params violations diffs
              valid-from]}]
  (when ds
    (jdbc/execute! ds
      [(str "INSERT INTO sew_trial_outcomes"
            " (_id, batch_id, strategy, final_state,"
            "  dispute_correct, appeal_triggered, slashed,"
            "  profit_honest, profit_malice, cm_fee, cm_afa,"
            "  invariants_ok, divergence_detected,"
            "  params_edn, violations_edn, diffs_edn, _valid_from)"
            " VALUES ("
            (xtdb/sql-str id)                          ", "
            (xtdb/sql-str (xtdb/kw->str batch-id))    ", "
            (xtdb/sql-str (xtdb/kw->str strategy))    ", "
            (xtdb/sql-str (xtdb/kw->str final-state)) ", "
            (xtdb/sql-bool dispute-correct?)           ", "
            (xtdb/sql-bool appeal-triggered?)          ", "
            (xtdb/sql-bool slashed?)                   ", "
            (xtdb/sql-long profit-honest)              ", "
            (xtdb/sql-long profit-malice)              ", "
            (xtdb/sql-long cm-fee)                     ", "
            (xtdb/sql-long cm-afa)                     ", "
            (xtdb/sql-bool invariants-ok?)             ", "
            (xtdb/sql-bool divergence?)                ", "
            (xtdb/sql-str (xtdb/->edn params))        ", "
            (xtdb/sql-str (xtdb/->edn violations))    ", "
            (xtdb/sql-str (xtdb/->edn diffs))         ", "
            (xtdb/sql-ts valid-from)
            ")")])))

;; ---------------------------------------------------------------------------
;; sew_escrow_events — writes
;; ---------------------------------------------------------------------------

(defn insert-sew-escrow-event!
  "Insert one escrow state-transition event.

   Keys:
     :id          — unique event id string
     :trial-id    — parent trial id
     :workflow-id — integer (0 for single-escrow trials)
     :event-type  — keyword, e.g. :sew/created, :sew/disputed, :sew/resolved
     :escrow-state — keyword (:pending :disputed :released :refunded :resolved)
     :block-time  — long (simulated unix timestamp)
     :valid-from  — java.util.Date (same as block-time converted to Date)

   No-op when ds is nil."
  [ds {:keys [id trial-id workflow-id event-type escrow-state block-time valid-from]}]
  (when ds
    (jdbc/execute! ds
      [(str "INSERT INTO sew_escrow_events"
            " (_id, trial_id, workflow_id, event_type, escrow_state,"
            "  block_time, _valid_from)"
            " VALUES ("
            (xtdb/sql-str id)                              ", "
            (xtdb/sql-str trial-id)                        ", "
            (xtdb/sql-long workflow-id)                    ", "
            (xtdb/sql-str (xtdb/kw->str event-type))      ", "
            (xtdb/sql-str (xtdb/kw->str escrow-state))    ", "
            (xtdb/sql-long block-time)                     ", "
            (xtdb/sql-ts valid-from)
            ")")])))

;; ---------------------------------------------------------------------------
;; sew_trial_outcomes — reads
;; ---------------------------------------------------------------------------

(defn- row->trial-outcome [row]
  (cond-> {:trial/id             (:_id row)
           :trial/batch-id       (some-> (:batch_id row) keyword)
           :trial/strategy       (some-> (:strategy row) keyword)
           :trial/final-state    (some-> (:final_state row) keyword)
           :trial/dispute-correct?  (boolean (:dispute_correct row))
           :trial/appeal-triggered? (boolean (:appeal_triggered row))
           :trial/slashed?          (boolean (:slashed row))
           :trial/profit-honest     (:profit_honest row)
           :trial/profit-malice     (:profit_malice row)
           :trial/cm-fee            (:cm_fee row)
           :trial/cm-afa            (:cm_afa row)
           :trial/invariants-ok?    (boolean (:invariants_ok row))
           :trial/divergence?       (boolean (:divergence_detected row))}
    (:params_edn row)     (assoc :trial/params     (xtdb/parse-edn (:params_edn row)))
    (:violations_edn row) (assoc :trial/violations (xtdb/parse-edn (:violations_edn row)))
    (:diffs_edn row)      (assoc :trial/diffs      (xtdb/parse-edn (:diffs_edn row)))))

(defn sew-trial-outcomes
  "Return all trial outcome rows for a batch, ordered by insertion.

   Options:
     :batch-id  — string/keyword filter (omit to return all batches)
     :strategy  — keyword filter (:honest | :malicious | ...)
     :limit     — max rows (default unbounded)

   Returns a vector of :trial/* namespaced maps.
   No-op (returns []) when ds is nil."
  ([ds] (sew-trial-outcomes ds {}))
  ([ds {:keys [batch-id strategy limit]}]
   (if (nil? ds)
     []
     (let [clauses (cond-> ["1=1"]
                     batch-id (conj (str "batch_id = '" (xtdb/kw->str batch-id) "'"))
                     strategy (conj (str "strategy = '"  (xtdb/kw->str strategy)  "'")))
           where   (clojure.string/join " AND " clauses)
           sql     (cond-> (str "SELECT * FROM sew_trial_outcomes WHERE " where)
                     limit (str " LIMIT " limit))]
       (mapv row->trial-outcome
             (jdbc/execute! ds [sql] xtdb/opts))))))

(defn- inst->iso ^String [^java.util.Date d]
  (.format (java.time.format.DateTimeFormatter/ISO_INSTANT)
           (.toInstant d)))

(defn sew-trial-outcomes-at
  "Return trial outcomes AS OF a specific valid time (bitemporal query).
   Returns a vector of :trial/* namespaced maps, or [] when ds is nil."
  [ds valid-at]
  (if (nil? ds)
    []
    (mapv row->trial-outcome
          (jdbc/execute! ds
            [(str "SELECT * FROM sew_trial_outcomes "
                  "FOR VALID_TIME AS OF TIMESTAMP '"
                  (inst->iso valid-at) "'")]
            xtdb/opts))))

;; ---------------------------------------------------------------------------
;; sew_escrow_events — reads
;; ---------------------------------------------------------------------------

(defn sew-escrow-events-for-trial
  "Return all escrow state-transition events for a trial, ordered by block_time.
   Returns a vector of event maps, or [] when ds is nil."
  [ds trial-id]
  (if (nil? ds)
    []
    (mapv (fn [row]
            {:event/id           (:_id row)
             :event/trial-id     (:trial_id row)
             :event/workflow-id  (:workflow_id row)
             :event/type         (some-> (:event_type row) keyword)
             :event/escrow-state (some-> (:escrow_state row) keyword)
             :event/block-time   (:block_time row)})
          (jdbc/execute! ds
            ["SELECT * FROM sew_escrow_events WHERE trial_id = ? ORDER BY block_time ASC"
             trial-id]
            xtdb/opts))))

;; ---------------------------------------------------------------------------
;; Aggregate helpers (pure — no database required)
;; ---------------------------------------------------------------------------

(defn summarise-batch
  "Compute summary statistics over a vector of :trial/* outcome maps.

   Returns:
     {:n              — total trials
      :by-strategy    — {strategy {:n :slashed :divergent :invariant-failures}}
      :by-final-state — {final-state count}
      :profit-honest  {:min :max :mean}
      :profit-malice  {:min :max :mean}}"
  [outcomes]
  (let [n    (count outcomes)
        by-s (group-by :trial/strategy outcomes)
        by-f (group-by :trial/final-state outcomes)
        mean (fn [xs k] (if (seq xs) (double (/ (reduce + (map k xs)) (count xs))) 0.0))
        min* (fn [xs k] (when (seq xs) (apply min (map k xs))))
        max* (fn [xs k] (when (seq xs) (apply max (map k xs))))]
    {:n              n
     :by-strategy    (into {}
                           (map (fn [[s rows]]
                                  [s {:n                  (count rows)
                                      :slashed            (count (filter :trial/slashed? rows))
                                      :divergent          (count (filter :trial/divergence? rows))
                                      :invariant-failures (count (remove :trial/invariants-ok? rows))}])
                                by-s))
     :by-final-state (into {} (map (fn [[f rows]] [f (count rows)]) by-f))
     :profit-honest  {:min  (min* outcomes :trial/profit-honest)
                      :max  (max* outcomes :trial/profit-honest)
                      :mean (mean outcomes :trial/profit-honest)}
     :profit-malice  {:min  (min* outcomes :trial/profit-malice)
                      :max  (max* outcomes :trial/profit-malice)
                      :mean (mean outcomes :trial/profit-malice)}}))
