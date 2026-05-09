(ns resolver-sim.scenario.projection
  "Terminal world-state projection for trace-end mechanism-property validation.

   Produces a stable, minimal projection of a replay result. Validators in
   resolver-sim.scenario.equilibrium receive only this projection — never raw
   world internals. This decouples validators from implementation details and
   keeps tests, docs, and TraceEquivalence integration stable.

   This namespace is pure — no I/O, no DB, no side effects.
   It currently reads SEW-specific world-snapshot fields and metrics; 
   this structure provides a template for future protocol integration.")

;; ---------------------------------------------------------------------------
;; Terminal-state helpers
;; ---------------------------------------------------------------------------

(def ^:private terminal-escrow-states
  "Escrow states from which no further transitions are possible."
  #{:released :refunded :cancelled :timeout})

(defn- terminal-state? [state]
  (contains? terminal-escrow-states (keyword (or state ""))))

(defn- map-delta
  "Return per-key (new-old) deltas for numeric maps."
  [old-map new-map]
  (let [keys-all (into #{} (concat (keys (or old-map {})) (keys (or new-map {}))))]
    (reduce (fn [m k]
              (let [old-v (long (get old-map k 0))
                    new-v (long (get new-map k 0))
                    d     (- new-v old-v)]
                (if (zero? d) m (assoc m k d))))
            {}
            keys-all)))

;; ---------------------------------------------------------------------------
;; Projection
;; ---------------------------------------------------------------------------

(defn trace-end-projection
  "Produce a stable, minimal projection of a replay result for use by
   mechanism-property and equilibrium-concept validators.

   Returns a map with three keys:
     :terminal-world   — world state at the end of the trace
     :metrics          — accumulated metrics (plus nil slots for absent data)
     :trace-summary    — high-level trace statistics

   The caller (evaluate-equilibrium) passes this projection to every validator
   instead of the raw result, providing a stable, documented surface area.

   Returns nil when result has no trace (e.g. :outcome :invalid with 0 events)."
  [result]
  (let [trace       (:trace result [])
        last-entry  (last trace)
        world       (when last-entry (:world last-entry))]
    (when world
      (let [live-states  (get world :live-states {})
            escrows      (into {} (map (fn [[id s]] [id (keyword (or s ""))]) live-states))
            all-terminal (every? (fn [[_ s]] (terminal-state? s)) escrows)

            metrics      (:metrics result {})

            ;; Build agent-id -> address map from the scenario agents list.
            ;; Propagated by replay-scenario for SPE wealth lookup.
            agents-by-id (reduce (fn [m a]
                                   (let [id   (or (:id a) "")
                                         addr (or (:address a) id)]
                                     (assoc m id addr)))
                                 {}
                                 (:agents result []))

            ;; Derive escalation levels actually observed in trace
            escalation-levels
            (into #{} (keep (fn [entry]
                              (let [action (get entry :action "")]
                                (when (re-find #"escalat" action)
                                  (get-in entry [:extra :level]))))
                            trace))

            ;; Collect actors from trace entries
            actors
            (into #{} (keep :agent trace))

            ;; Distill decisions for SPE validation.
            ;; :address resolves agent-id -> protocol address so SPE can look up
            ;; resolver-stakes (keyed by address, not agent ID).
            decisions (vec (keep (fn [entry]
                                   (when (contains? #{"raise_dispute" "escalate_dispute" "release"
                                                      "sender_cancel" "recipient_cancel" "execute_resolution"}
                                                    (:action entry))
                                     (let [agent-id (:agent entry)
                                           addr     (get agents-by-id agent-id agent-id)]
                                       (assoc (select-keys entry [:seq :time :agent :action :extra])
                                               :address addr))))
                                  trace))

            transitions (map vector trace (rest trace))

            token-deltas
            (reduce (fn [acc [a b]]
                      (let [held-d (map-delta (get-in a [:world :total-held] {}) (get-in b [:world :total-held] {}))
                            fee-d  (map-delta (get-in a [:world :total-fees] {}) (get-in b [:world :total-fees] {}))
                            toks   (into #{} (concat (keys held-d) (keys fee-d)))]
                        (reduce (fn [m t]
                                  (-> m
                                      (update-in [t :held-delta] (fnil + 0) (long (get held-d t 0)))
                                      (update-in [t :fee-delta] (fnil + 0) (long (get fee-d t 0)))
                                      (update-in [t :claimable-delta] (fnil + 0) 0)))
                                acc
                                toks)))
                    {}
                    transitions)

            pending-lifecycle
            (reduce (fn [{:keys [created cleared superseded] :as acc} [a b]]
                      (let [p0 (long (get-in a [:world :pending-count] 0))
                            p1 (long (get-in b [:world :pending-count] 0))
                            action (str (or (:action b) ""))]
                        (cond
                          (> p1 p0) (update acc :created + (- p1 p0))
                          (< p1 p0) (-> acc
                                        (update :cleared + (- p0 p1))
                                        (update :superseded + (if (re-find #"escalat" action) (- p0 p1) 0)))
                          :else acc)))
                    {:created 0 :cleared 0 :superseded 0}
                    transitions)

            stake-flow
            (let [first-stakes (get-in (first trace) [:world :resolver-stakes] {})
                  last-stakes  (get world :resolver-stakes {})
                  keys-all     (into #{} (concat (keys first-stakes) (keys last-stakes)))]
              (reduce (fn [m r]
                        (assoc m r {:start     (long (get first-stakes r 0))
                                    :withdrawn 0
                                    :slashed   0
                                    :end       (long (get last-stakes r 0))}))
                      {}
                      keys-all))

            stake-flow
            (reduce (fn [acc [a b]]
                      (let [before (get-in a [:world :resolver-stakes] {})
                            after  (get-in b [:world :resolver-stakes] {})
                            deltas (map-delta before after)
                            action (str (or (:action b) ""))]
                        (reduce (fn [m [resolver d]]
                                  (if (neg? d)
                                    (cond
                                      (re-find #"withdraw" action) (update-in m [resolver :withdrawn] + (- d))
                                      (re-find #"slash|auto_cancel_disputed" action) (update-in m [resolver :slashed] + (- d))
                                      :else m)
                                    m))
                                acc
                                deltas)))
                    stake-flow
                    transitions)

            workflow-outcomes
            (into {}
                  (map (fn [[wf st]]
                         [wf {:terminal-state st
                              :path (case st
                                      :released :release
                                      :refunded :refund
                                      :cancelled :cancel
                                      :timeout :timeout
                                      :non-terminal)}])
                       escrows))]

        {:terminal-world
         {:escrows             escrows
          :total-held-by-token (get world :total-held {})
          :total-fees-by-token (get world :total-fees {})
          :escrow-amounts      (get world :escrow-amounts {})
          :dispute-resolvers   (get world :dispute-resolvers {})
          :dispute-levels      (get world :dispute-levels {})
          :pending-count       (get world :pending-count 0)
          :resolver-stakes     (get world :resolver-stakes {})
          :terminal?           all-terminal
          :all-terminal-states (into #{} (filter terminal-state? (vals escrows)))}

         :metrics
         {:total-escrows               (get metrics :total-escrows 0)
          :total-volume                (get metrics :total-volume 0)
          :disputes-triggered          (get metrics :disputes-triggered 0)
          :resolutions-executed        (get metrics :resolutions-executed 0)
          :pending-settlements-executed (get metrics :pending-settlements-executed 0)
          :attack-attempts             (get metrics :attack-attempts 0)
          :attack-successes            (get metrics :attack-successes 0)
          :rejected-attacks            (get metrics :rejected-attacks 0)
          :reverts                     (get metrics :reverts 0)
          :invariant-violations        (get metrics :invariant-violations 0)
          :double-settlements          (get metrics :double-settlements 0)
          :invalid-state-transitions   (get metrics :invalid-state-transitions 0)
          :funds-lost                  (get metrics :funds-lost 0)
          ;; nil when not populated by multi-epoch runner or payoff-ledger
          :coalition-net-profit        (get metrics :coalition-net-profit nil)
          :negative-payoff-count       (get metrics :negative-payoff-count nil)}

         :trace-summary
         {:events-count      (count trace)
          :actors            actors
          :dispute-count     (get metrics :disputes-triggered 0)
          :escalation-levels escalation-levels
          :terminal-time     (get world :block-time 0)
          :halt-reason       (:halt-reason result nil)}

         :money-movement-summary
         {:workflow-outcomes workflow-outcomes
          :post-dispute-transfers {:unknown {:to-sender 0 :to-recipient 0 :fees 0}}
          :pending-lifecycle {:unknown pending-lifecycle}
          :token-deltas token-deltas}

         :stake-flow-summary stake-flow

         :decisions decisions
         :raw-trace trace}))))
