(ns resolver-sim.contract-model.replay
  "Open-world scenario replay engine for the SEW contract model.

   Consumes a scenario map (v1 schema, loaded from JSON) and replays its events
   deterministically against the contract-model state machine.  Invariants are
   enforced after every successful state transition.  On violation the replay
   halts immediately and returns a full step trace.

   ## Authority model
   Python (and in Phase 2, gRPC clients) are treated as UNTRUSTED input.
   All validation is performed in Clojure regardless of upstream checks.

   ## Time model
   Each event carries an absolute :time (block-timestamp).  The world's
   :block-time is advanced to the event's :time before dispatch.  Events with
   time < current :block-time are REJECTED (not silently re-stamped).

   ## Workflow-id model
   create-escrow assigns IDs sequentially: (count :escrow-transfers).
   The assigned id is echoed in the trace :extra field of each create step.
   In gRPC mode the client must read this value — it must never be predicted.

   ## Action semantics
   - Rejected actions ({:ok false}) are non-fatal — they model protocol reverts.
     The world is not modified; the trace records the rejection with its error.
   - Invariant violations ARE fatal — replay halts, outcome is :fail.
   - Unknown actions are non-fatal in file-replay mode (treated as reverts).
     In gRPC mode they should be escalated to fatal by the server layer.
   - Unknown agents → non-fatal revert (never an exception).

   ## Invariants enforced per step
   After every successful transition:
     1. check-all     — solvency (strict =), fee non-negative
     2. check-transition — terminal state irreversibility

   ## Layering
   replay.clj is part of contract_model/ and must NOT import db/*, io/*, or
   clojure.java.io.  File loading lives in io/scenarios.clj; callers chain
   (io.scenarios/load-scenario-file path) with replay-scenario themselves."
  (:require [clojure.data.json                      :as json]
            [clojure.string                         :as str]
            [resolver-sim.contract-model.diff       :as diff]
            [resolver-sim.contract-model.types      :as t]
            [resolver-sim.contract-model.state-machine :as sm]
            [resolver-sim.contract-model.lifecycle     :as lc]
            [resolver-sim.contract-model.resolution    :as res]
            [resolver-sim.contract-model.registry      :as reg]
            [resolver-sim.contract-model.authority     :as auth]
            [resolver-sim.io.trace-metadata            :as meta]

            [resolver-sim.contract-model.invariants :as inv]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private supported-schema-version "1.0")

;; Maximum safe amount: prevents Long overflow in (* amount fee-bps)
;; fee-bps max = 10000; Long/MAX_VALUE / 10000 ≈ 922_337_203_685_477
(def ^:private max-safe-amount 922337203685477)

;; ---------------------------------------------------------------------------
;; JSON serialisation helpers (output only — key loading lives in io/scenarios.clj)
;; ---------------------------------------------------------------------------

(defn- kw->json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn- kw-val->str [_k v]
  (if (keyword? v) (name v) v))

;; ---------------------------------------------------------------------------
;; Agent index
;; ---------------------------------------------------------------------------

(defn- agents-by-id [agents]
  (into {} (map (juxt :id identity) agents)))

(defn- resolve-address
  "Return {:ok true :address addr} or {:ok false :error :unknown-agent}.
   Never throws."
  [agent-index agent-id]
  (if-let [agent (get agent-index agent-id)]
    {:ok true :address (:address agent)}
    {:ok false :error :unknown-agent :detail {:agent-id agent-id}}))

;; ---------------------------------------------------------------------------
;; Protocol snapshot
;; ---------------------------------------------------------------------------

(defn- build-snapshot [pp]
  (t/make-module-snapshot
   {:escrow-fee-bps               (get pp :resolver-fee-bps 50)
    :resolution-module            (get pp :resolution-module nil)
    :appeal-window-duration       (get pp :appeal-window-duration 0)
    :max-dispute-duration         (get pp :max-dispute-duration 2592000)
    :appeal-bond-protocol-fee-bps (get pp :appeal-bond-protocol-fee-bps 0)
    :dispute-resolver             (get pp :dispute-resolver nil)
    :appeal-bond-bps              (get pp :appeal-bond-bps 0)
    :resolver-bond-bps            (get pp :resolver-bond-bps 1000) ; 10%
    :appeal-bond-amount           (get pp :appeal-bond-amount 0)
    :reversal-slash-bps           (get pp :reversal-slash-bps 0)
    :fraud-slash-bps              (get pp :fraud-slash-bps 5000)
    :challenge-window-duration    (get pp :challenge-window-duration 0)
    :challenge-bond-bps           (get pp :challenge-bond-bps 0)
    :challenge-bounty-bps         (get pp :challenge-bounty-bps 0)}))


;; ---------------------------------------------------------------------------
;; Release strategy: only sender (:from) may release in simulation mode
;; ---------------------------------------------------------------------------

(defn- sender-only-release [world workflow-id caller]
  (let [et (t/get-transfer world workflow-id)]
    {:allowed? (= caller (:from et)) :reason-code 0}))

;; ---------------------------------------------------------------------------
;; Public: agent validation + context construction (used by gRPC session layer)
;; ---------------------------------------------------------------------------

(defn validate-agents
  "Validate a list of agent maps {:id :address :type ...} for structural correctness.
   Returns {:ok true} or {:ok false :error kw :detail {...}}.

   Checks: non-empty, unique :id values, unique :address values."
  [agents]
  (let [id-counts   (frequencies (map :id agents))
        addr-counts (frequencies (map :address agents))
        dup-ids     (keys (filter (fn [[_ n]] (> n 1)) id-counts))
        dup-addrs   (keys (filter (fn [[_ n]] (> n 1)) addr-counts))]
    (cond
      (empty? agents)   {:ok false :error :no-agents}
      (seq dup-ids)     {:ok false :error :duplicate-agent-ids
                         :detail {:duplicates (vec dup-ids)}}
      (seq dup-addrs)   {:ok false :error :duplicate-agent-addresses
                         :detail {:duplicates (vec dup-addrs)}}
      :else             {:ok true})))

(defn build-context
  "Build an execution context from a list of agent maps and a protocol-params map.
   Agents must already be keywordized ({:id :address :type ...}).
   Returns a context map with keys:
     :agent-index          — {agent-id → agent}
     :snapshot             — ModuleSnapshot (frozen at session start)
     :escalation-fn        — fn or nil
     :resolution-module-fn — fn or nil (single-resolver / DR1 mode)
     :resolution-level-map — map or nil (Kleros multi-level / DR3 mode)

   Resolution module modes (mutually exclusive):

   Single-resolver (DR1/DR2):
     protocol-params contains :resolution-module — an address string.
     :resolution-module-fn is set; :resolution-level-map is nil.

   Kleros multi-level (DR3):
     protocol-params contains :escalation-resolvers — {level-kw → addr} map,
     e.g. {:0 addr0 :1 addr1 :2 addr2} (keywordized from JSON string keys).
     :resolution-level-map is set; :resolution-module-fn is nil.
     A derived escalation-fn is wired automatically (override with explicit arg).
     apply-action 'execute_resolution' builds the module fn per-step using the
     live world dispute level, so it always reads the correct escalation round.
     Callers must also pass :resolution-module (any non-empty string) in
     protocol-params to activate Priority 2 in the module snapshot.

   Direct (no module):
     Neither key present. escalation-fn is nil unless passed explicitly.

   Callers must run validate-agents first — duplicate IDs are silently overwritten
   in the agent-index."
  ([agents protocol-params] (build-context agents protocol-params nil))
  ([agents protocol-params external-escalation-fn]
   (let [rm-addr   (get protocol-params :resolution-module nil)
         esc-map   (get protocol-params :escalation-resolvers nil)
         ;; Convert {:0 addr0 :1 addr1} → {0 addr0 1 addr1}
         level-map (when esc-map
                     (into {} (map (fn [[k v]] [(parse-long (name k)) v]) esc-map)))
         ;; Single-resolver mode only when no level-map (Kleros takes precedence)
         rm-fn     (when (and rm-addr (not= rm-addr "") (nil? level-map))
                     (auth/make-default-resolution-module rm-addr))
         ;; Kleros escalation-fn: given current-level, returns next resolver or error
         esc-fn    (or external-escalation-fn
                       (when level-map
                         (fn [_world _wf-id _caller current-level]
                           (let [next-level   (inc current-level)
                                 new-resolver (get level-map next-level)]
                             (if new-resolver
                               {:ok true :new-resolver new-resolver}
                               {:ok false :error :escalation-not-allowed})))))]
     {:agent-index          (agents-by-id agents)
      :snapshot             (build-snapshot protocol-params)
      :escalation-fn        esc-fn
      :resolution-module-fn rm-fn
      :resolution-level-map level-map})))

;; ---------------------------------------------------------------------------
;; Input validation — Clojure-side, applied before entering the event loop
;; ---------------------------------------------------------------------------

(defn validate-scenario
  "Validate a scenario map for structural correctness before replay.
   Returns {:ok true} or {:ok false :error kw :detail {...}}.

   Validations (all enforced regardless of upstream Pydantic checks):
     - :schema-version must be '1.0'
     - :agents must be non-empty
     - agent :id values must be unique (no silent overwrites in agent-index)
     - agent :address values must be unique (no shared address ambiguity)
     - :events must be non-empty
     - event :seq must be contiguous from 0 (no gaps, no duplicates)
     - event :time values must be monotonically non-decreasing
     - event :time values must be >= :initial-block-time
     - all event :agent refs must exist in :agents"
  [scenario]
  (let [version     (:schema-version scenario)
        agents      (:agents scenario)
        events      (sort-by :seq (:events scenario))
        known-ids   (set (map :id agents))
        init-time   (get scenario :initial-block-time 1000)
        ;; Duplicate detection
        id-counts   (frequencies (map :id agents))
        addr-counts (frequencies (map :address agents))
        dup-ids     (keys (filter (fn [[_ n]] (> n 1)) id-counts))
        dup-addrs   (keys (filter (fn [[_ n]] (> n 1)) addr-counts))]
    (cond
      (not= version supported-schema-version)
      {:ok false :error :unsupported-schema-version
       :detail {:expected supported-schema-version :got version}}

      (empty? agents)
      {:ok false :error :no-agents}

      ;; Duplicate agent IDs — would cause silent authority hijack via agents-by-id
      (seq dup-ids)
      {:ok false :error :duplicate-agent-ids
       :detail {:duplicates (vec dup-ids)}}

      ;; Duplicate agent addresses — ambiguous authority resolution
      (seq dup-addrs)
      {:ok false :error :duplicate-agent-addresses
       :detail {:duplicates (vec dup-addrs)}}

      (empty? events)
      {:ok false :error :no-events}

      ;; Seq contiguity: must be exactly 0, 1, 2, …, n-1
      (not= (mapv :seq events) (vec (range (count events))))
      {:ok false :error :non-contiguous-event-seq
       :detail {:got (mapv :seq events)}}

      ;; Time monotonicity
      (some (fn [[a b]] (> (:time a) (:time b)))
            (partition 2 1 events))
      {:ok false :error :non-monotonic-event-time
       :detail {:violations (vec (filter (fn [[a b]] (> (:time a) (:time b)))
                                         (partition 2 1 events)))}}

      ;; All event times >= initial-block-time
      (some #(< (:time %) init-time) events)
      {:ok false :error :event-time-before-initial
       :detail {:initial-block-time init-time
                :violations         (mapv :time (filter #(< (:time %) init-time) events))}}

      ;; Agent references
      (some #(not (contains? known-ids (:agent %))) events)
      {:ok false :error :unknown-agent-in-event
       :detail {:bad-refs (vec (filter #(not (contains? known-ids (:agent %))) events))}}

      :else {:ok true})))

;; ---------------------------------------------------------------------------
;; Event dispatcher (multimethod on :action string)
;; ---------------------------------------------------------------------------

(defmulti ^:private apply-action
  "Apply one action to world.
   Returns {:ok bool :world world' :error kw :extra {...}}
   Never throws — all error paths return {:ok false :error kw}."
  (fn [_ctx _world event] (:action event)))

(defmethod apply-action "create_escrow"
  [{:keys [agent-index snapshot]} world event]
  (let [p       (:params event)
        ar      (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [caller (:address ar)
            token  (:token p)
            to     (:to p)
            amount (:amount p)]
        ;; Guard against Long overflow in fee arithmetic
        (if (or (nil? amount) (<= amount 0) (> amount max-safe-amount))
          {:ok false :error :amount-out-of-safe-range
           :detail {:amount amount :max max-safe-amount}}
          (let [cres     (get p :custom-resolver)
                settings (t/make-escrow-settings {:custom-resolver cres})
                result   (lc/create-escrow world caller token to amount settings snapshot)]
            (if (:ok result)
              (assoc result :extra {:workflow-id (:workflow-id result)})
              result)))))))

(defmethod apply-action "raise_dispute"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (lc/raise-dispute world (get-in event [:params :workflow-id]) (:address ar)))))

(defmethod apply-action "execute_resolution"
  [{:keys [agent-index resolution-module-fn resolution-level-map]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p               (:params event)
            workflow-id     (:workflow-id p)
            is-release      (get p :is-release true)
            resolution-hash (get p :resolution-hash "0xsimhash")
            ;; Kleros mode: build module fn per-step so it reads the live dispute level
            ;; from world (level changes after each escalation).
            effective-rm-fn (or (when resolution-level-map
                                  (auth/make-kleros-module
                                   resolution-level-map
                                   #(t/dispute-level world %)))
                                resolution-module-fn)]
        (res/execute-resolution world workflow-id (:address ar)
                                is-release resolution-hash effective-rm-fn)))))

(defmethod apply-action "execute_pending_settlement"
  [_ctx world event]
  (res/execute-pending-settlement world (get-in event [:params :workflow-id])))

(defmethod apply-action "automate_timed_actions"
  [_ctx world event]
  (res/automate-timed-actions world (get-in event [:params :workflow-id])))

(defmethod apply-action "release"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (lc/release world (get-in event [:params :workflow-id])
                  (:address ar) sender-only-release))))

(defmethod apply-action "sender_cancel"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      ;; nil cancel-strategy → mutual-consent path only
      (lc/sender-cancel world (get-in event [:params :workflow-id]) (:address ar) nil))))

(defmethod apply-action "recipient_cancel"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (lc/recipient-cancel world (get-in event [:params :workflow-id]) (:address ar) nil))))

(defmethod apply-action "auto_cancel_disputed"
  [_ctx world event]
  (lc/auto-cancel-disputed-escrow world (get-in event [:params :workflow-id])))

(defmethod apply-action "escalate_dispute"
  [{:keys [agent-index escalation-fn]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [workflow-id (get-in event [:params :workflow-id])
            result      (res/escalate-dispute world workflow-id (:address ar) escalation-fn)]
        (if (:ok result)
          (assoc result :extra {:new-level    (:new-level result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "rotate_dispute_resolver"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [workflow-id   (get-in event [:params :workflow-id])
            new-resolver  (get-in event [:params :new-resolver])
            result        (res/rotate-dispute-resolver world workflow-id new-resolver)]
        (if (:ok result)
          (assoc result :extra {:old-resolver (:old-resolver result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "register_stake"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [amount (get-in event [:params :amount] 0)]
        (t/ok (reg/register-stake world (:address ar) amount))))))

(defmethod apply-action "propose_fraud_slash"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p             (:params event)
            workflow-id   (:workflow-id p)
            resolver-addr (:resolver-addr p)
            amount        (:amount p)]
        (res/propose-fraud-slash world workflow-id (:address ar) resolver-addr amount)))))

(defmethod apply-action "challenge_resolution"
  [{:keys [agent-index escalation-fn]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (res/challenge-resolution world (get-in event [:params :workflow-id]) (:address ar) escalation-fn))))

(defmethod apply-action "appeal_slash"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (res/appeal-slash world (get-in event [:params :workflow-id]) (:address ar)))))

(defmethod apply-action "resolve_appeal"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p (:params event)]
        (res/resolve-appeal world (:workflow-id p) (:address ar) (:upheld? p))))))

(defmethod apply-action "execute_fraud_slash"
  [_ctx world event]
  (res/execute-fraud-slash world (get-in event [:params :workflow-id])))

(defmethod apply-action "advance_time"
  [_ctx world _event]
  ;; Time is already advanced before dispatch — this is a pure no-op.
  (t/ok world))

(defmethod apply-action :default
  [_ctx _world event]
  {:ok false :error :unknown-action :detail {:action (:action event)}})

;; ---------------------------------------------------------------------------
;; Per-step invariant checks
;; ---------------------------------------------------------------------------

(defn- check-invariants-single
  "Single-world invariants (solvency, fee non-negative).
   Returns {:ok? bool :violations map-or-nil}."
  [world]
  (let [r (inv/check-all world)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

(defn- check-invariants-transition
  "Cross-world invariants (terminal state irreversibility).
   Returns {:ok? bool :violations map-or-nil}."
  [world-before world-after]
  (let [r (inv/check-transition world-before world-after)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

;; ---------------------------------------------------------------------------
;; Lean world snapshot for trace output
;; ---------------------------------------------------------------------------

(defn- world-snapshot [world]
  {:block-time         (:block-time world)
   :escrow-count       (count (:escrow-transfers world))
   :total-held         (:total-held world)
   :total-fees         (:total-fees world)
   :pending-count      (count (filter #(:exists (val %)) (:pending-settlements world)))
   :live-states        (into {} (map (fn [[id et]] [id (:escrow-state et)])
                                     (:escrow-transfers world)))
   :dispute-levels     (into {} (:dispute-levels world))
   :dispute-resolvers  (into {} (map (fn [[id et]] [id (:dispute-resolver et)])
                                     (:escrow-transfers world)))
   :resolver-rotations (into {} (:resolver-rotations world))
   :escrow-amounts     (into {} (map (fn [[id et]] [id (:amount-after-fee et)])
                                     (:escrow-transfers world)))
   :resolver-stakes     (:resolver-stakes world)
   :bond-distribution   (:bond-distribution world)})

;; ---------------------------------------------------------------------------
;; Single-step processor — public for gRPC server use
;; ---------------------------------------------------------------------------

(defn process-step
  "Apply one scenario event to the world state.

   context — {:agent-index {id→agent} :snapshot ModuleSnapshot}
   world   — current canonical world state
   event   — {:seq n :time t :agent id :action str :params {...}}

   Time contract: event :time must be >= world :block-time.
   If violated, returns {:ok? false :world world :halted? false} with
   :result :rejected and :error :time-regression.  This is a revert, not a halt,
   because time regression indicates a Python ordering bug, not a state violation.

   Returns:
     {:ok?         bool    — false only when an invariant is violated (halt)
      :world        world' — new world (or unchanged world on revert/halt)
      :trace-entry  map    — full step record
      :halted?      bool   — true iff replay must stop (invariant violated)}"
  [context world event]
  (let [event-time (:time event)
        now        (:block-time world)]

    ;; Reject backward time — do NOT silently re-stamp
    (if (< event-time now)
      {:ok?    true   ; not a halt — it's a revert
       :world  world
       :trace-entry {:seq            (:seq event)
                     :time           event-time
                     :agent          (:agent event)
                     :action         (:action event)
                     :result         :rejected
                     :error          :time-regression
                     :extra          nil
                     :invariants-ok? true
                     :violations     nil
                     :world          (world-snapshot world)
                      :projection     (diff/projection world)
                      :projection-hash (diff/projection-hash world)}
       :halted? false}

      (let [;; Advance block-time
            world-t    (if (> event-time now) (assoc world :block-time event-time) world)

            ;; Dispatch action
            result     (try
                         (apply-action context world-t event)
                         (catch Exception e
                           {:ok false :error :dispatch-exception
                            :detail {:message (.getMessage e)}}))
            ok?        (:ok result)
            world-next (if ok? (:world result) world-t)

            ;; Per-step invariants (only on successful transitions)
            inv-single (when ok? (check-invariants-single world-next))
            inv-trans  (when ok? (check-invariants-transition world-t world-next))
            violated?  (and ok?
                            (not (and (:ok? inv-single) (:ok? inv-trans))))

            ;; Merge violation reports
            all-violations (when violated?
                             (merge
                              (when-not (:ok? inv-single) (:violations inv-single))
                              (when-not (:ok? inv-trans)  (:violations inv-trans))))]

        {:ok?    (and ok? (not violated?))
         :world  (if violated? world-t world-next)
         :trace-entry
         {:seq            (:seq event)
          :time           event-time
          :agent          (:agent event)
          :action         (:action event)
          :result         (cond violated? :invariant-violated
                                ok?       :ok
                                :else     :rejected)
          :error          (when-not ok? (:error result))
          :extra          (:extra result)
          :invariants-ok? (if ok? (and (:ok? inv-single) (:ok? inv-trans)) true)
          :violations     all-violations
          :trace-metadata {:transition/type (meta/transition-type (:action event))
                           :effect/type     (meta/effect-type (cond violated? :invariant-violated
                                                                    ok?       :ok
                                                                    :else     :rejected))
                           :resolution/path (meta/resolution-path (:action event))}
          ;; On invariant violation we halt and roll back to world-t.
          ;; The snapshot shown to the client must match the canonical world
          ;; that will be retained — showing the violated world-next would
          ;; give observers a false picture of the engine's internal state.
          :world          (world-snapshot (if violated? world-t world-next))
           :projection     (diff/projection (if violated? world-t world-next))
           :projection-hash (diff/projection-hash (if violated? world-t world-next))}
         :halted? violated?}))))

;; ---------------------------------------------------------------------------
;; Metrics accumulator
;; ---------------------------------------------------------------------------

(defn- zero-metrics []
  {:total-escrows                0
   :total-volume                 0
   :disputes-triggered           0
   :resolutions-executed         0
   :pending-settlements-executed 0
   :attack-attempts              0
   :attack-successes             0
   :reverts                      0
   :invariant-violations         0})

(defn- accum-metrics [metrics event trace-entry agent-index]
  (let [action    (:action event)
        accepted? (= :ok (:result trace-entry))
        agent     (get agent-index (:agent event))
        attack?   (= "attacker" (:type agent))]
    (cond-> metrics
      (and accepted? (= action "create_escrow"))
      (-> (update :total-escrows inc)
          (update :total-volume + (get-in event [:params :amount] 0)))

      (and accepted? (= action "raise_dispute"))
      (update :disputes-triggered inc)

      (and accepted? (= action "execute_resolution"))
      (update :resolutions-executed inc)

      (and accepted? (= action "execute_pending_settlement"))
      (update :pending-settlements-executed inc)

      (and attack? accepted?)
      (update :attack-successes inc)

      attack?
      (update :attack-attempts inc)

      (not accepted?)
      (update :reverts inc)

      (:violations trace-entry)
      (update :invariant-violations inc))))

;; ---------------------------------------------------------------------------
;; Workflow-id alias resolution
;;
;; Events may use string aliases (e.g. "wf0") in :params :workflow-id to
;; reference an escrow created earlier in the same scenario.  The alias is
;; resolved from wf-alias-map, which is populated when a create_escrow step
;; with a :save-wf-as annotation succeeds.
;; ---------------------------------------------------------------------------

(defn- resolve-wf-alias
  "Substitute a string :workflow-id alias.
   Returns {:ok true :event resolved-event} or {:ok false :error :unresolved-alias}."
  [event wf-alias-map]
  (let [wf-val (get-in event [:params :workflow-id])]
    (if (string? wf-val)
      (if-let [int-id (get wf-alias-map wf-val)]
        {:ok true :event (assoc-in event [:params :workflow-id] int-id)}
        {:ok false :error :unresolved-alias :alias wf-val :seq (:seq event)})
      {:ok true :event event})))

;; ---------------------------------------------------------------------------
;; Public: replay-scenario
;; ---------------------------------------------------------------------------

(defn replay-scenario
  "Replay a parsed scenario map (v1 schema) against the SEW contract model.

   Validates the scenario structure before entering the event loop.
   Returns {:ok false :error kw} if the scenario is structurally invalid.

   Events may carry :save-wf-as \"wfN\" on create_escrow steps; subsequent
   events may then reference that alias as {:workflow-id \"wfN\"} instead of
   an integer.  Aliases are resolved lazily in the event loop.

   Returns:
     {:outcome          :pass | :fail | :invalid
      :scenario-id      str
      :events-processed nat-int
      :halted-at-seq    nat-int | nil
      :halt-reason      kw | nil
      :trace            [{step-trace}]
      :metrics          {metrics-map}}"
  [scenario]
  (let [validation (validate-scenario scenario)]
    (if-not (:ok validation)
      {:outcome          :invalid
       :scenario-id      (:scenario-id scenario)
       :events-processed 0
       :halted-at-seq    nil
       :halt-reason      (:error validation)
       :detail           (:detail validation)
       :trace            []
       :metrics          (zero-metrics)}

      (let [agent-list  (:agents scenario)
            pp          (get scenario :protocol-params {})
            context     (build-context agent-list pp)
            agent-index (:agent-index context)
            init-time   (get scenario :initial-block-time 1000)
            world0      (t/empty-world init-time)
            events      (sort-by :seq (:events scenario))]
        (loop [world        world0
               events       events
               trace        []
               metrics      (zero-metrics)
               wf-alias-map {}]
          (if (empty? events)
            {:outcome          :pass
             :scenario-id      (:scenario-id scenario)
             :events-processed (count trace)
             :halted-at-seq    nil
             :halt-reason      nil
             :trace            trace
             :metrics          metrics}
            (let [raw-event   (first events)
                  alias-res   (resolve-wf-alias raw-event wf-alias-map)]
              (if-not (:ok alias-res)
                {:outcome          :invalid
                 :scenario-id      (:scenario-id scenario)
                 :events-processed (count trace)
                 :halted-at-seq    (:seq raw-event)
                 :halt-reason      :unresolved-alias
                 :detail           (dissoc alias-res :ok)
                 :trace            trace
                 :metrics          metrics}
                (let [event       (:event alias-res)
                      step        (process-step context world event)
                      entry       (:trace-entry step)
                      new-trace   (conj trace entry)
                      new-metrics (accum-metrics metrics event entry agent-index)
                      ;; Register alias when create_escrow succeeds and :save-wf-as is set
                      new-alias-map
                      (if (and (= "create_escrow" (:action event))
                               (= :ok (:result entry))
                               (:save-wf-as raw-event))
                        (assoc wf-alias-map
                               (:save-wf-as raw-event)
                               (get-in entry [:extra :workflow-id]))
                        wf-alias-map)]
                  (if (:halted? step)
                    {:outcome          :fail
                     :scenario-id      (:scenario-id scenario)
                     :events-processed (count new-trace)
                     :halted-at-seq    (:seq event)
                     :halt-reason      :invariant-violation
                     :trace            new-trace
                     :metrics          (update new-metrics :invariant-violations inc)}
                    (recur (:world step) (rest events) new-trace new-metrics new-alias-map)))))))))))

;; ---------------------------------------------------------------------------
;; Public: result->json-str
;; ---------------------------------------------------------------------------

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  (json/write-str result :key-fn kw->json-key :value-fn kw-val->str))
