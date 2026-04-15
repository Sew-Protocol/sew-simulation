(ns resolver-sim.contract-model.resolution
  "Pure Clojure port of BaseEscrow resolution and appeal-window logic.

   Covers:
     execute-resolution          — _executeResolution (resolver submits outcome)
     execute-pending-settlement  — executePendingSettlement (after appeal deadline)
     automate-timed-actions      — automateTimedActions (keeper dispatch)
     escalate-dispute            — escalateDispute (appeal to next round)

   All functions return {:ok bool :world world' :error keyword}."
  (:require [resolver-sim.contract-model.types         :as t]
            [resolver-sim.contract-model.state-machine :as sm]
            [resolver-sim.contract-model.authority     :as auth]
            [resolver-sim.contract-model.accounting    :as acct]))

;; ---------------------------------------------------------------------------
;; Internal: finalize helpers (no accounting — see lifecycle for that)
;; ---------------------------------------------------------------------------

(defn- finalize-release [world workflow-id]
  (let [et    (t/get-transfer world workflow-id)
        token (:token et)
        amt   (:amount-after-fee et)]
    (-> world
        (acct/sub-held token amt)
        (update :pending-settlements dissoc workflow-id)
        (sm/apply-transition! workflow-id :released))))

(defn- finalize-refund [world workflow-id]
  (let [et    (t/get-transfer world workflow-id)
        token (:token et)
        amt   (:amount-after-fee et)]
    (-> world
        (acct/sub-held token amt)
        (update :pending-settlements dissoc workflow-id)
        (sm/apply-transition! workflow-id :refunded))))

;; ---------------------------------------------------------------------------
;; execute-resolution
;;
;; Mirrors: BaseEscrow._executeResolution
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. caller must be an authorized dispute resolver
;;   3. state must be :disputed
;;
;; Appeal window logic (SettlementOps.computeResolutionExecution):
;;   If snap.appeal-window-duration > 0:
;;     → store PendingSettlement, do NOT finalize immediately
;;   Else:
;;     → finalize immediately (release or refund)
;;
;; When escalation occurs (dispute is escalated to a higher level):
;;   Any existing pending-settlement is cancelled (see _validateAndPrepareEscalation).
;;   This function models the normal case (no concurrent escalation).
;; ---------------------------------------------------------------------------

(defn execute-resolution
  "Submit a resolution decision for a :disputed escrow.

   caller              — address of msg.sender (must be authorized resolver)
   is-release          — true = release to recipient, false = refund to sender
   resolution-hash     — bytes32 (opaque string in the model)
   resolution-module-fn — (fn [wf caller] → {:authorized? bool}) or nil"
  [world workflow-id caller is-release resolution-hash resolution-module-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not (auth/authorized-resolver? world workflow-id caller resolution-module-fn))
    (t/fail :not-authorized-resolver)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    ;; Prevent resolution flip: reject if a pending settlement already exists
    (:exists (t/get-pending world workflow-id))
    (t/fail :resolution-already-pending)

    :else
    (let [snap           (t/get-snapshot world workflow-id)
          appeal-dur     (:appeal-window-duration snap 0)
          now            (:block-time world)
          ;; Mirrors SettlementOps.computeResolutionExecution:
          ;; if isFinalRound (currentRound >= MAX_ROUND) → shouldExecute = true
          ;; (no appeal window, decision is immediately final)
          final-round?   (t/final-round? world workflow-id)]
      (if (or final-round? (not (pos? appeal-dur)))
        ;; Final round or no appeal window: execute immediately
        (t/ok (if is-release
                (finalize-release world workflow-id)
                (finalize-refund  world workflow-id)))
        ;; Appeal window active: defer settlement
        (let [pending (t/make-pending-settlement
                       {:exists          true
                        :is-release      is-release
                        :appeal-deadline (+ now appeal-dur)
                        :resolution-hash resolution-hash})
              world'  (assoc-in world [:pending-settlements workflow-id] pending)]
          (t/ok world'))))))

;; ---------------------------------------------------------------------------
;; execute-pending-settlement
;;
;; Mirrors: BaseEscrow.executePendingSettlement
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. pending-settlement must exist (pending.exists = true)
;;   3. state must be :disputed
;;   4. block-time >= appeal-deadline
;; ---------------------------------------------------------------------------

(defn execute-pending-settlement
  "Execute a deferred settlement after the appeal window has closed."
  [world workflow-id]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not (:exists (t/get-pending world workflow-id)))
    (t/fail :no-pending-settlement)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (< (:block-time world) (:appeal-deadline (t/get-pending world workflow-id)))
    (t/fail :appeal-window-not-expired)

    :else
    (let [pending (t/get-pending world workflow-id)]
      (t/ok (if (:is-release pending)
              (finalize-release world workflow-id)
              (finalize-refund  world workflow-id))))))

;; ---------------------------------------------------------------------------
;; automate-timed-actions
;;
;; Mirrors: BaseEscrow.automateTimedActions
;;
;; Dispatch order (matches the contract's if/else if chain):
;;   1. ACTION_EXECUTE_PENDING  — pending-settlement executable?
;;   2. ACTION_AUTO_RELEASE     — auto-release-time passed?
;;   3. ACTION_AUTO_CANCEL      — auto-cancel-time passed?
;;   4. ACTION_NONE             — no action, return {:ok true :world world :action :none}
;;
;; Returns {:ok bool :world world' :action kw} where action is one of:
;;   :execute-pending :auto-release :auto-cancel :none
;; ---------------------------------------------------------------------------

(defn automate-timed-actions
  "Dispatch timed keeper actions for workflow-id.

   Returns {:ok true :world world' :action kw} — even when action is :none,
   to simplify caller logic."
  [world workflow-id]
  (if (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)
    (cond
      ;; Priority 1: pending settlement ready to execute
      (sm/pending-settlement-executable? world workflow-id)
      (let [r (execute-pending-settlement world workflow-id)]
        (if (:ok r)
          (assoc r :action :execute-pending)
          r))

      ;; Priority 2: auto-release
      (sm/auto-release-due? world workflow-id)
      (let [et    (t/get-transfer world workflow-id)
            token (:token et)
            amt   (:amount-after-fee et)
            w'    (-> world
                      (update-in [:total-held token] (fnil - 0) amt)
                      (update :pending-settlements dissoc workflow-id)
                      (sm/apply-transition! workflow-id :released))]
        (assoc (t/ok w') :action :auto-release))

      ;; Priority 3: auto-cancel
      (sm/auto-cancel-due? world workflow-id)
      (let [et    (t/get-transfer world workflow-id)
            token (:token et)
            amt   (:amount-after-fee et)
            w'    (-> world
                      (update-in [:total-held token] (fnil - 0) amt)
                      (update :pending-settlements dissoc workflow-id)
                      (sm/apply-transition! workflow-id :refunded))]
        (assoc (t/ok w') :action :auto-cancel))

      :else
      (assoc (t/ok world) :action :none))))

;; ---------------------------------------------------------------------------
;; cancel-pending-on-escalation
;;
;; Internal building block: _validateAndPrepareEscalation deletes
;; pendingSettlements[workflowId] before escalation proceeds.
;; ---------------------------------------------------------------------------

(defn cancel-pending-on-escalation
  "Cancel pending-settlement when a dispute is being escalated.
   Called as a side-effect of escalation before the new level is set.
   Returns the updated world (not a result map)."
  [world workflow-id]
  (update world :pending-settlements dissoc workflow-id))

;; ---------------------------------------------------------------------------
;; escalate-dispute
;;
;; Mirrors: BaseEscrow.escalateDispute
;;          + DisputeOps.computeEscalation
;;          + DecentralizedResolutionModule.executeEscalation
;;
;; Guards:
;;   1. workflow-id must exist
;;   2. state must be :disputed
;;   3. caller must be :from or :to (participant only can appeal)
;;   4. current level must be < max-dispute-level (cannot escalate beyond MAX_ROUND)
;;   5. a pending settlement must exist — escalation is an appeal of a resolver's
;;      decision, not a pre-emptive jump to the next level.  Without this guard
;;      a malicious party can bypass all lower-level resolvers immediately.
;;   6. escalation-fn must return {:ok true :new-resolver addr}
;;
;; Effects (in order, matching Solidity):
;;   a. Clear pending-settlement (_validateAndPrepareEscalation)
;;   b. Increment dispute-level
;;   c. Update et.dispute-resolver to the new resolver
;;
;; escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})
;;   Models the combined DisputeOps.computeEscalation + DRM.executeEscalation call.
;;   Pass nil to simulate "no resolution module / escalation not allowed".
;;
;; Returns {:ok true :world world' :new-level n :new-resolver addr}
;;         or {:ok false :error kw}
;; ---------------------------------------------------------------------------

(defn escalate-dispute
  "Escalate a :disputed escrow to the next resolution round.

   Requires a pending settlement to exist: escalation is an appeal of an
   existing resolver decision, not a unilateral level-skip.

   escalation-fn — (fn [world workflow-id caller level] → {:ok bool :new-resolver addr})
                   Pass nil to simulate 'escalation not configured'."
  [world workflow-id caller escalation-fn]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (let [et (t/get-transfer world workflow-id)]
      (and (not= caller (:from et)) (not= caller (:to et))))
    (t/fail :not-participant)

    (t/final-round? world workflow-id)
    (t/fail :escalation-not-allowed)

    ;; Escalation is an appeal: a resolver must have already submitted a
    ;; resolution (creating a pending settlement) before a party may escalate.
    (not (:exists (t/get-pending world workflow-id)))
    (t/fail :no-resolution-to-appeal)

    (nil? escalation-fn)
    (t/fail :escalation-not-configured)

    :else
    (let [current-level (t/dispute-level world workflow-id)
          esc-result    (escalation-fn world workflow-id caller current-level)]
      (if-not (:ok esc-result)
        (t/fail (or (:error esc-result) :escalation-not-allowed))
        (let [new-level    (inc current-level)
              new-resolver (:new-resolver esc-result)
              world'       (-> world
                               (cancel-pending-on-escalation workflow-id)
                               (assoc-in [:dispute-levels workflow-id] new-level)
                               (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                         new-resolver))]
          (assoc (t/ok world')
                 :new-level    new-level
                 :new-resolver new-resolver))))))

;; ---------------------------------------------------------------------------
;; rotate-dispute-resolver
;;
;; Models a governance-triggered resolver rotation on an in-flight dispute.
;; Guards: workflow must exist + be in :disputed state; new-resolver non-nil.
;; Effects:
;;   - Updates et.dispute-resolver to new-resolver
;;   - Appends to world :resolver-rotations {workflow-id [{:from :to :at}]}
;; ---------------------------------------------------------------------------

(defn rotate-dispute-resolver
  "Governance-triggered resolver rotation for an in-flight dispute.
   Records the rotation so invariants and scenarios can detect governance attacks."
  [world workflow-id new-resolver]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not= :disputed (t/escrow-state world workflow-id))
    (t/fail :transfer-not-in-dispute)

    (or (nil? new-resolver) (= "" new-resolver))
    (t/fail :invalid-new-resolver)

    :else
    (let [old-resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])
          rotation     {:from old-resolver :to new-resolver :at (:block-time world)}
          world'       (-> world
                           (assoc-in [:escrow-transfers workflow-id :dispute-resolver]
                                     new-resolver)
                           (update-in [:resolver-rotations workflow-id]
                                      (fnil conj []) rotation))]
      (assoc (t/ok world') :old-resolver old-resolver :new-resolver new-resolver))))
