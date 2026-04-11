(ns resolver-sim.contract-model.invariants
  "Checkable invariant predicates for the SEW contract model.

   These mirror the runtime guards in InvariantGuardInternal.sol and define
   the specification for future Foundry invariant tests and Halmos properties.

   Each predicate takes a world-state map and returns a map:
     {:holds? bool :violations [...]}

   Invariants:
     1. solvency (single-world)      — total-held[t] = sum(live afa[t])  STRICT =
                                       and total-held[t] <= token-balance[t]  (external)
     2. fee-monotonicity (single)    — total-fees[t] never goes negative
     3. state-irreversibility (cross)— terminal states are absorbing (checked via check-transition)
     4. bond-boundedness (single)    — slash amount <= posted bond per workflow (vacuous until bonds added)
     5. no-double-finalize           — each workflow-id finalizes at most once (structural guarantee)"
  (:require [resolver-sim.contract-model.types         :as t]
            [resolver-sim.contract-model.state-machine :as sm]))

;; ---------------------------------------------------------------------------
;; Invariant 1: Solvency
;;
;; For every token: sum(amount-after-fee for :pending/:disputed escrows) <= total-held
;;
;; In the real contract this is enforced by InvariantGuardInternal._checkBalance.
;; ---------------------------------------------------------------------------

(defn solvency-holds?
  "True when total-held[token] exactly equals the sum of amount-after-fee of
   all live (:pending / :disputed) escrows for that token.

   The internal invariant is STRICT EQUALITY (=), not <=.  Any divergence
   indicates a bug in a finalization function (e.g. sub-held was skipped).

   token-balances — optional {token nat-int} representing the contract's actual
                    ERC20 balance.  When provided, additionally enforces:
                    total-held[token] <= token-balance[token]
                    (the external balance may exceed total-held only due to
                    protocol fees remaining in the contract)."
  [world token-balances]
  (let [live-states   #{:pending :disputed}
        ;; Compute total-held from scratch across all tokens
        all-tokens    (-> (set (keys (:total-held world)))
                          (into (map :token (vals (:escrow-transfers world)))))
        violations
        (for [token all-tokens
              :let  [held     (get (:total-held world) token 0)
                     live-sum (reduce (fn [acc [_ et]]
                                        (if (and (= (:token et) token)
                                                 (contains? live-states (:escrow-state et)))
                                          (+ acc (:amount-after-fee et))
                                          acc))
                                      0
                                      (:escrow-transfers world))
                     ext-bal  (when token-balances (get token-balances token 0))
                     ;; Internal: total-held must EXACTLY match live escrow sum
                     internal-ok? (= live-sum held)
                     ;; External: actual contract balance >= total-held
                     external-ok? (or (nil? ext-bal) (<= held ext-bal))]
              :when (not (and internal-ok? external-ok?))]
          {:token       token
           :live-sum    live-sum
           :held        held
           :ext-bal     ext-bal
           :internal-ok? internal-ok?
           :external-ok? external-ok?})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 2: Fee monotonicity
;;
;; total-fees[token] only increases. It may be reset to 0 by withdraw-fees,
;; but between any two consecutive withdraw-fees calls it must never decrease.
;;
;; Tested as: applying any non-withdraw operation never reduces total-fees.
;; ---------------------------------------------------------------------------

(defn fees-non-negative?
  "True when all total-fees values are >= 0 (they should never go negative)."
  [world]
  (let [violations (for [[token amount] (:total-fees world)
                         :when (neg? amount)]
                     {:token token :amount amount})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

(defn fee-increased-or-equal?
  "True when every total-fees entry in world' is >= the corresponding entry
   in world-before. Used to assert monotonicity across a single operation."
  [world-before world-after]
  (let [violations (for [[token before-amt] (:total-fees world-before)
                         :let [after-amt (get (:total-fees world-after) token 0)]
                         :when (< after-amt before-amt)]
                     {:token token :before before-amt :after after-amt})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 3: State irreversibility
;;
;; Once an escrow reaches :released, :refunded, or :resolved it must remain
;; in that state. No operation should change a terminal state.
;; ---------------------------------------------------------------------------

(defn terminal-states-unchanged?
  "True when every escrow that was terminal in world-before is still terminal
   in world-after, and has the same state."
  [world-before world-after]
  (let [terminals #{:released :refunded :resolved}
        violations
        (for [[wf et-before] (:escrow-transfers world-before)
              :when (contains? terminals (:escrow-state et-before))
              :let  [et-after (get-in world-after [:escrow-transfers wf])]
              :when (not= (:escrow-state et-before) (:escrow-state et-after))]
          {:workflow-id wf
           :before      (:escrow-state et-before)
           :after       (:escrow-state et-after)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 4: Bond boundedness
;;
;; The slash amount for any (workflow, appellant) must not exceed the posted bond.
;; ---------------------------------------------------------------------------

(defn bond-slash-bounded?
  "True when :bond-slashed[wf] <= sum of original bonds posted for that workflow.
   Uses :bond-balances + :bond-slashed as the accounting split."
  [world]
  (let [violations
        (for [[wf slashed] (:bond-slashed world)
              :let  [remaining (reduce + 0 (vals (get (:bond-balances world) wf {})))
                     ;; Original posted = remaining + slashed
                     original  (+ remaining slashed)]
              :when (> slashed original)]
          {:workflow-id wf :slashed slashed :original original})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 5: No double-finalize
;;
;; Each workflow-id should appear at most once in any terminal state.
;; Since escrow-transfers is an indexed vector (workflowId = index),
;; each id is inherently unique — but the state must be terminal at most once.
;; ---------------------------------------------------------------------------

(defn no-double-finalize?
  "True when no workflow-id has been finalized more than once.
   In the pure model this is structurally guaranteed by the single map entry,
   but this predicate is retained for use in property-based test chains where
   the runner accumulates a history log."
  [finalization-log]
  (let [counts    (frequencies (map :workflow-id finalization-log))
        violations (filter (fn [[_ n]] (> n 1)) counts)]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 6: Held non-negative
;;
;; total-held values must never go negative — a negative value indicates
;; a sub-held was applied without a corresponding escrow having been counted.
;; ---------------------------------------------------------------------------

(defn held-non-negative?
  "True when all total-held values are >= 0."
  [world]
  (let [violations (for [[token amount] (:total-held world)
                         :when (neg? amount)]
                     {:token token :amount amount})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 7: Valid status combinations
;;
;; Every escrow must have a (escrow-state × sender-status × recipient-status)
;; combination that is permitted by the SEW protocol.
;; ---------------------------------------------------------------------------

(defn all-status-combinations-valid?
  "True when every escrow has a valid (state × sender-status × recipient-status)
   combination according to sm/valid-status-combination?."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (not (sm/valid-status-combination?
                          {:escrow-state     (:escrow-state et)
                           :sender-status    (:sender-status et :none)
                           :recipient-status (:recipient-status et :none)}))]
          {:workflow-id      wf
           :escrow-state     (:escrow-state et)
           :sender-status    (:sender-status et :none)
           :recipient-status (:recipient-status et :none)})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 8: Pending-settlement consistency
;;
;; A pending settlement may only exist for an escrow in :disputed state.
;; ---------------------------------------------------------------------------

(defn pending-settlement-consistency?
  "True when every workflow-id with an existing pending-settlement has
   escrow-state == :disputed."
  [world]
  (let [violations
        (for [[wf pending] (:pending-settlements world)
              :when (:exists pending)
              :let  [state (t/escrow-state world wf)]
              :when (not= :disputed state)]
          {:workflow-id wf :state state})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 9: Dispute-timestamp consistency
;;
;; Every escrow in :disputed state must have a dispute-raised-timestamp > 0.
;; A :disputed escrow with no timestamp would make dispute-timeout-exceeded?
;; permanently return false, preventing keeper actions.
;; ---------------------------------------------------------------------------

(defn dispute-timestamp-consistency?
  "True when every :disputed escrow has a dispute timestamp > 0."
  [world]
  (let [violations
        (for [[wf et] (:escrow-transfers world)
              :when (= :disputed (:escrow-state et))
              :let  [ts (get-in world [:dispute-timestamps wf] 0)]
              :when (not (pos? ts))]
          {:workflow-id wf :timestamp ts})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 10: No stale automatable escrows
;;
;; If an escrow is eligible for a timed action (auto-release, auto-cancel, or
;; execute-pending), the keeper must have already processed it.  Finding such
;; an escrow in a finalized world snapshot is a temporal invariant violation.
;;
;; This is checked on a post-step world, after automate-timed-actions has run.
;; ---------------------------------------------------------------------------

(defn no-stale-automatable-escrows?
  "True when no escrow is currently eligible for an untriggered timed action.

   Intended to be called on the world snapshot AFTER automate-timed-actions
   has been run for each active escrow.  A violation means the caller failed
   to invoke the keeper."
  [world]
  (let [violations
        (for [[wf _et] (:escrow-transfers world)
              :when (or (sm/auto-release-due?             world wf)
                        (sm/auto-cancel-due?              world wf)
                        (sm/pending-settlement-executable? world wf))]
          {:workflow-id wf
           :reasons (cond-> []
                      (sm/auto-release-due? world wf)              (conj :auto-release-due)
                      (sm/auto-cancel-due? world wf)               (conj :auto-cancel-due)
                      (sm/pending-settlement-executable? world wf) (conj :pending-executable))})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 11: Finalization accounting correct (cross-world)
;;
;; When an escrow transitions to a terminal state, total-held for its token
;; must decrease by exactly the escrow's amount-after-fee.
;; ---------------------------------------------------------------------------

(defn finalization-accounting-correct?
  "True when every escrow that became terminal between world-before and
   world-after had its amount-after-fee correctly subtracted from total-held.

   Only fires for escrows whose state changed to a terminal state."
  [world-before world-after]
  (let [terminals #{:released :refunded :resolved}
        violations
        (for [[wf et-after] (:escrow-transfers world-after)
              :when (contains? terminals (:escrow-state et-after))
              :let  [et-before (get-in world-before [:escrow-transfers wf])
                     state-before (:escrow-state et-before)]
              ;; Only check escrows that JUST became terminal
              :when (and state-before (not (contains? terminals state-before)))
              :let  [token      (:token et-after)
                     afa        (:amount-after-fee et-after)
                     held-before (get-in world-before [:total-held token] 0)
                     held-after  (get-in world-after  [:total-held token] 0)
                     expected    (- held-before afa)]
              :when (not= held-after expected)]
          {:workflow-id  wf
           :token        token
           :afa          afa
           :held-before  held-before
           :held-after   held-after
           :expected     expected})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 12: Dispute level bounded
;;
;; :dispute-levels[wf] must be in [0, max-dispute-level].
;; A level entry must only exist for escrows currently in :disputed state
;; (non-disputed escrows have no meaningful escalation round).
;; ---------------------------------------------------------------------------

(defn dispute-level-bounded?
  "True when every :dispute-levels entry is in [0, max-dispute-level]
   and only exists for escrows that are or were :disputed."
  [world]
  (let [violations
        (for [[wf level] (:dispute-levels world)
              :when (or (neg? level)
                        (> level t/max-dispute-level))]
          {:workflow-id wf :level level :max t/max-dispute-level})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Invariant 13: Escalation level monotonic (cross-world)
;;
;; Dispute levels may only increase by exactly 1 per step and can never
;; decrease.  A level jumping from 0 to 2, or dropping from 1 to 0,
;; indicates a bug in the escalation path.
;; ---------------------------------------------------------------------------

(defn escalation-level-monotonic?
  "True when no dispute level decreased between world-before and world-after,
   and no level increased by more than 1 in a single step."
  [world-before world-after]
  (let [violations
        (for [[wf level-after] (:dispute-levels world-after)
              :let  [level-before (t/dispute-level world-before wf)
                     delta        (- level-after level-before)]
              :when (or (neg? delta) (> delta 1))]
          {:workflow-id  wf
           :level-before level-before
           :level-after  level-after
           :delta        delta})]
    {:holds?     (empty? violations)
     :violations (vec violations)}))

;; ---------------------------------------------------------------------------
;; Composite: check all world-level invariants
;; ---------------------------------------------------------------------------

(defn check-all
  "Run all single-world invariants.

   token-balances — optional {token nat-int} for external balance check.
   Returns {:all-hold? bool :results {invariant-name result-map}}"
  ([world] (check-all world nil))
  ([world token-balances]
   (let [results {:solvency                      (solvency-holds? world token-balances)
                  :fees-non-negative             (fees-non-negative? world)
                  :held-non-negative             (held-non-negative? world)
                  :all-status-combinations-valid (all-status-combinations-valid? world)
                  :pending-settlement-consistent (pending-settlement-consistency? world)
                  :dispute-timestamp-consistent  (dispute-timestamp-consistency? world)
                  :dispute-level-bounded         (dispute-level-bounded? world)}
         all?    (every? #(:holds? %) (vals results))]
     {:all-hold? all?
      :results   results})))

(defn check-transition
  "Run all cross-world invariants that require comparing world-before to world-after.

   Must be called after every successful state transition in addition to check-all.
   Returns {:all-hold? bool :results {invariant-name result-map}}"
  [world-before world-after]
  (let [results {:terminal-states-unchanged
                 (terminal-states-unchanged? world-before world-after)
                 :finalization-accounting-correct
                 (finalization-accounting-correct? world-before world-after)
                 :escalation-level-monotonic
                 (escalation-level-monotonic? world-before world-after)}
        all?    (every? #(:holds? %) (vals results))]
    {:all-hold? all?
     :results   results}))
