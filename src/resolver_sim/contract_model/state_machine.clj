(ns resolver-sim.contract-model.state-machine
  "Pure Clojure port of StateManagementLibrary.sol.

   Each function mirrors one Solidity state transition, including the
   caller-site precondition guards that appear in BaseEscrow.sol.

   All functions return {:ok bool :world world' :error keyword}.
   No side effects. World state is immutable; transitions return a new map.

   ## Declarative transition graph

   `allowed-transitions` is the single authoritative source of which
   EscrowState → EscrowState edges are legal.  Every function that changes
   :escrow-state goes through `apply-transition!`, which consults the graph
   and throws a programming-error exception if an illegal edge is attempted.
   The caller-visible result maps use `valid-transition?` in their guards to
   return typed {:ok false :error kw} results before the edge is attempted.

   ## Note on :resolved

   transitionToResolved() is defined in StateManagementLibrary.sol but is
   NEVER called by any BaseEscrow production code path (verified from source).
   Disputes always terminate in :released or :refunded.  :resolved is retained
   in the graph for enum completeness and Foundry/halmos compatibility, and is
   reachable only after terminal-transfer-done? confirms accounting is settled."
  (:require [resolver-sim.contract-model.types :as t]))

;; ---------------------------------------------------------------------------
;; Declarative transition graph
;; ---------------------------------------------------------------------------

(def allowed-transitions
  "Authoritative EscrowState → #{reachable states} graph.
   Mirrors BaseEscrow.sol call sites and StateManagementLibrary.sol guards.

   :none     — pre-creation sentinel; createEscrow produces :pending directly.
   :resolved — defined in enum and library; no production call site currently
               reaches it.  Guards in transition-to-resolved enforce that
               accounting is settled before it may be entered."
  {:none      #{:pending}
   :pending   #{:disputed :released :refunded}
   :disputed  #{:released :refunded :resolved}
   :resolved  #{}
   :released  #{}
   :refunded  #{}})

(defn valid-transition?
  "True when :from → :to is an edge in `allowed-transitions`."
  [from to]
  (contains? (get allowed-transitions from #{}) to))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- update-transfer
  "Apply f to the EscrowTransfer map for workflow-id."
  [world workflow-id f & args]
  (apply update-in world [:escrow-transfers workflow-id] f args))

(defn- set-escrow-state
  "Set :escrow-state for workflow-id.  Asserts via the transition graph —
   throws ex-info on an illegal edge (programming error, not a protocol revert)."
  [world workflow-id new-state]
  (let [old-state (t/escrow-state world workflow-id)]
    (when-not (valid-transition? old-state new-state)
      (throw (ex-info "Illegal state transition"
                      {:from old-state :to new-state :workflow-id workflow-id})))
    (update-transfer world workflow-id assoc :escrow-state new-state)))

;; ---------------------------------------------------------------------------
;; Guard: workflow must exist
;; ---------------------------------------------------------------------------

(defn- assert-workflow
  "Return {:ok false :error :invalid-workflow-id} when workflow-id is absent."
  [world workflow-id]
  (when-not (t/valid-workflow-id? world workflow-id)
    (t/fail :invalid-workflow-id)))

;; ---------------------------------------------------------------------------
;; Public: apply-transition!
;;
;; Lower-level helper for finalization functions (lifecycle, resolution) that
;; have already validated their own guards and just need to commit a state
;; change through the graph.  Returns the updated world directly (not a result
;; map) so it can be used inside -> threads.
;; Throws ex-info on an illegal edge — same as set-escrow-state.
;; ---------------------------------------------------------------------------

(defn apply-transition!
  "Commit a checked state change for workflow-id → new-state.
   Returns the new world map.  Throws on an illegal graph edge."
  [world workflow-id new-state]
  (set-escrow-state world workflow-id new-state))

;; ---------------------------------------------------------------------------
;; Valid status-combination predicate
;;
;; Mirrors the protocol constraints on (EscrowState × SenderStatus × RecipientStatus).
;;
;; From StateManagementLibrary.sol and BaseEscrow.sol call-site analysis:
;;
;;   :none     — only :none/:none statuses permitted
;;   :pending  — AGREE_TO_CANCEL allowed for either party; RAISE_DISPUTE is
;;               transitional and should not persist in :pending
;;   :disputed — exactly one party has :raise-dispute, the other :none;
;;               AGREE_TO_CANCEL is forbidden (senderCancel/recipientCancel
;;               require state == :pending, so can't set it after raiseDispute)
;;   terminal  — statuses are frozen; any combination is valid
;; ---------------------------------------------------------------------------

(defn valid-status-combination?
  "True when {:escrow-state :sender-status :recipient-status} is a valid
   combination according to the SEW protocol.

   Invalid examples:
     :disputed + :agree-to-cancel + *       — AGREE_TO_CANCEL can't be set in disputed
     :disputed + :none + :none              — at least one party must have RAISE_DISPUTE
     :disputed + :raise-dispute + :raise-dispute  — only one party can initiate (guards)
     :pending  + :raise-dispute + *         — RAISE_DISPUTE means state already :disputed"
  [{:keys [escrow-state sender-status recipient-status]}]
  (let [terminals #{:released :refunded :resolved}]
    (cond
      ;; Terminal states: statuses are frozen; accept any combination
      (contains? terminals escrow-state)
      true

      ;; :none — pre-creation or uninitialized; both statuses must be :none
      (= :none escrow-state)
      (and (= :none sender-status) (= :none recipient-status))

      ;; :pending — AGREE_TO_CANCEL is valid; RAISE_DISPUTE is not
      ;; (RAISE_DISPUTE on :pending is a transient state that should have
      ;;  immediately become :disputed via transitionToDisputed)
      (= :pending escrow-state)
      (and (contains? #{:none :agree-to-cancel} sender-status)
           (contains? #{:none :agree-to-cancel} recipient-status))

      ;; :disputed — exactly one party has RAISE_DISPUTE; neither has AGREE_TO_CANCEL
      (= :disputed escrow-state)
      (and (contains? #{:none :raise-dispute} sender-status)
           (contains? #{:none :raise-dispute} recipient-status)
           (not= :agree-to-cancel sender-status)
           (not= :agree-to-cancel recipient-status)
           ;; At least one party must have raised the dispute
           (or (= :raise-dispute sender-status)
               (= :raise-dispute recipient-status))
           ;; Both cannot have raised (only one raiseDispute call is accepted)
           (not (and (= :raise-dispute sender-status)
                     (= :raise-dispute recipient-status))))

      :else false)))

;; ---------------------------------------------------------------------------
;; transitionToDisputed
;;
;; Mirrors: StateManagementLibrary.transitionToDisputed
;;          + raiseDispute preconditions in BaseEscrow
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. escrow-state must be :pending
;;   3. caller must be :from or :to of the escrow
;; ---------------------------------------------------------------------------

(defn transition-to-disputed
  "Transition a :pending escrow to :disputed.

   Sets senderStatus = :raise-dispute when caller is :from,
   recipientStatus = :raise-dispute when caller is :to.
   Records dispute-raised-timestamp = block-time.

   Returns {:ok true :world world'} or {:ok false :error kw}."
  [world workflow-id caller]
  (or (assert-workflow world workflow-id)
      (let [et (t/get-transfer world workflow-id)]
        (cond
          (not (valid-transition? (:escrow-state et) :disputed))
          (t/fail :transfer-not-pending)

          (and (not= caller (:from et)) (not= caller (:to et)))
          (t/fail :not-participant)

          :else
          (let [is-sender? (= caller (:from et))
                world'     (-> world
                               (set-escrow-state workflow-id :disputed)
                               ;; Set caller's status to :raise-dispute and clear the
                               ;; counterparty's status — mirrors the Solidity invariant
                               ;; that :agree-to-cancel cannot persist once disputed.
                               (update-transfer workflow-id
                                               (if is-sender?
                                                 #(assoc % :sender-status    :raise-dispute
                                                           :recipient-status  :none)
                                                 #(assoc % :recipient-status :raise-dispute
                                                           :sender-status     :none)))
                               (assoc-in [:dispute-timestamps workflow-id]
                                         (:block-time world)))]
            (t/ok world'))))))

;; ---------------------------------------------------------------------------
;; transitionToReleased
;;
;; Mirrors: StateManagementLibrary.transitionToReleased
;;          + _releaseEscrowTransfer preconditions
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. valid-transition? :pending/:disputed → :released
;;      (release can be called from PENDING via direct release, or from
;;       DISPUTED via _executeResolution → _releaseEscrowTransfer)
;; ---------------------------------------------------------------------------

(defn transition-to-released
  "Transition a :pending or :disputed escrow to :released.

   Returns {:ok true :world world'} or {:ok false :error kw}."
  [world workflow-id]
  (or (assert-workflow world workflow-id)
      (let [state (t/escrow-state world workflow-id)]
        (if-not (valid-transition? state :released)
          (t/fail :invalid-state-for-release)
          (t/ok (set-escrow-state world workflow-id :released))))))

;; ---------------------------------------------------------------------------
;; transitionToRefunded
;;
;; Mirrors: StateManagementLibrary.transitionToRefunded
;;          + _cancelAndRefund preconditions
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. valid-transition? :pending/:disputed → :refunded
;; ---------------------------------------------------------------------------

(defn transition-to-refunded
  "Transition a :pending or :disputed escrow to :refunded.

   Returns {:ok true :world world'} or {:ok false :error kw}."
  [world workflow-id]
  (or (assert-workflow world workflow-id)
      (let [state (t/escrow-state world workflow-id)]
        (if-not (valid-transition? state :refunded)
          (t/fail :invalid-state-for-refund)
          (t/ok (set-escrow-state world workflow-id :refunded))))))

;; ---------------------------------------------------------------------------
;; transitionToResolved
;;
;; Mirrors: StateManagementLibrary.transitionToResolved
;;
;; NOTE: No production BaseEscrow code path currently calls this function.
;; Disputes resolve to :released or :refunded directly.  :resolved is retained
;; for enum completeness and future Foundry/halmos invariant tests.
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. valid-transition? :disputed → :resolved
;;   3. terminal-transfer-done? — accounting must already be settled
;;      (total-held excludes this escrow's amount) before marking resolved.
;;      This prevents :resolved being reached without proper fund movement.
;; ---------------------------------------------------------------------------

(defn- terminal-transfer-done?
  "True when the escrow's amount-after-fee is no longer reflected in total-held
   for its token, i.e. the release/refund transfer accounting has already run.

   Computed by checking that total-held[token] == sum of all OTHER live escrows
   for that token (meaning this escrow's afa has been subtracted)."
  [world workflow-id]
  (let [et    (t/get-transfer world workflow-id)
        token (:token et)
        afa   (:amount-after-fee et)
        held  (get-in world [:total-held token] 0)
        live-states #{:pending :disputed}
        other-live  (reduce (fn [acc [wf e]]
                              (if (and (not= wf workflow-id)
                                       (= (:token e) token)
                                       (contains? live-states (:escrow-state e)))
                                (+ acc (:amount-after-fee e))
                                acc))
                            0
                            (:escrow-transfers world))]
    ;; If held == other-live, this escrow's afa was already removed
    (= held other-live)))

(defn transition-to-resolved
  "Transition a :disputed escrow to :resolved.

   Guard: terminal-transfer-done? must be true — accounting must be settled
   before resolved may be entered.  This prevents the state being used as a
   shortcut that skips fund movement.

   Returns {:ok true :world world'} or {:ok false :error kw}."
  [world workflow-id]
  (or (assert-workflow world workflow-id)
      (let [state (t/escrow-state world workflow-id)]
        (cond
          (not (valid-transition? state :resolved))
          (t/fail :transfer-not-in-dispute)

          (not (terminal-transfer-done? world workflow-id))
          (t/fail :resolution-without-settlement)

          :else
          (t/ok (set-escrow-state world workflow-id :resolved))))))

;; ---------------------------------------------------------------------------
;; Mutual-consent cancel state setters
;;
;; These are not transitions to terminal states — they record agreement
;; so the next check can decide whether to finalise.
;; ---------------------------------------------------------------------------

(defn set-sender-agree-to-cancel
  "Record senderStatus = :agree-to-cancel.
   Guard: state must be :pending, caller must be :from."
  [world workflow-id caller]
  (or (assert-workflow world workflow-id)
      (let [et (t/get-transfer world workflow-id)]
        (cond
          (not= :pending (:escrow-state et))
          (t/fail :transfer-not-pending)

          (not= caller (:from et))
          (t/fail :not-sender)

          :else
          (t/ok (update-transfer world workflow-id assoc :sender-status :agree-to-cancel))))))

(defn set-recipient-agree-to-cancel
  "Record recipientStatus = :agree-to-cancel.
   Guard: state must be :pending, caller must be :to."
  [world workflow-id caller]
  (or (assert-workflow world workflow-id)
      (let [et (t/get-transfer world workflow-id)]
        (cond
          (not= :pending (:escrow-state et))
          (t/fail :transfer-not-pending)

          (not= caller (:to et))
          (t/fail :not-recipient)

          :else
          (t/ok (update-transfer world workflow-id assoc :recipient-status :agree-to-cancel))))))

;; ---------------------------------------------------------------------------
;; Compound: mutual-cancel check
;; ---------------------------------------------------------------------------

(defn both-agreed-to-cancel?
  "True if both sender and recipient have set AGREE_TO_CANCEL."
  [world workflow-id]
  (let [et (t/get-transfer world workflow-id)]
    (and (= :agree-to-cancel (:sender-status et))
         (= :agree-to-cancel (:recipient-status et)))))

;; ---------------------------------------------------------------------------
;; Timed-action predicates
;; ---------------------------------------------------------------------------

(defn auto-release-due?
  "True when auto-release-time has passed and escrow is still :pending."
  [world workflow-id]
  (let [et    (t/get-transfer world workflow-id)
        t-rel (:auto-release-time et)]
    (and (= :pending (:escrow-state et))
         (pos? t-rel)
         (>= (:block-time world) t-rel))))

(defn auto-cancel-due?
  "True when auto-cancel-time has passed and escrow is still :pending."
  [world workflow-id]
  (let [et    (t/get-transfer world workflow-id)
        t-can (:auto-cancel-time et)]
    (and (= :pending (:escrow-state et))
         (pos? t-can)
         (>= (:block-time world) t-can))))

(defn dispute-timeout-exceeded?
  "True when max-dispute-duration has elapsed since raiseDispute and
   no pending-settlement exists."
  [world workflow-id]
  (let [state    (t/escrow-state world workflow-id)
        ts       (get-in world [:dispute-timestamps workflow-id] 0)
        snap     (t/get-snapshot world workflow-id)
        max-dur  (get snap :max-dispute-duration 0)
        pending  (t/get-pending world workflow-id)]
    (and (= :disputed state)
         (not (:exists pending))
         (pos? ts)
         (pos? max-dur)
         (>= (:block-time world) (+ ts max-dur)))))

(defn pending-settlement-executable?
  "True when pending-settlement exists, state is :disputed, and
   appeal-deadline has passed."
  [world workflow-id]
  (let [pending (t/get-pending world workflow-id)
        state   (t/escrow-state world workflow-id)]
    (and (:exists pending)
         (= :disputed state)
         (>= (:block-time world) (:appeal-deadline pending)))))
