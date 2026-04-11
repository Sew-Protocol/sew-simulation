(ns resolver-sim.contract-model.accounting
  "Pure Clojure port of EscrowVault balance and fee accounting, plus
   BondCollector fee deduction logic.

   Covers:
     - total-held-per-token tracking (add on create, sub on release/refund)
     - total-fees-per-token (monotonically increasing; withdraw-fees resets)
     - claimable-balances (push-transfer fallback; cleared on withdrawEscrow)
     - withdraw-fees
     - BondCollector appeal bond accounting

   All arithmetic uses integer division (uint256 truncation semantics)."
  (:require [resolver-sim.contract-model.types :as t]))

;; ---------------------------------------------------------------------------
;; total-held tracking
;; ---------------------------------------------------------------------------

(defn add-held
  "Increase total-held for token by amount. Called on createEscrow."
  [world token amount]
  (update-in world [:total-held token] (fnil + 0) amount))

(defn sub-held
  "Decrease total-held for token by amount. Called on release/refund.
   Does NOT guard against underflow — callers must have validated state."
  [world token amount]
  (update-in world [:total-held token] (fnil - 0) amount))

;; ---------------------------------------------------------------------------
;; total-fees tracking
;; ---------------------------------------------------------------------------

(defn record-fee
  "Accumulate fee into total-fees. Monotonically increasing.
   Mirrors FeeRecordingLibrary.recordFee in EscrowVault."
  [world token amount]
  (update-in world [:total-fees token] (fnil + 0) amount))

(defn withdraw-fees
  "Withdraw all accumulated fees for token.
   Sets total-fees[token] = 0 and returns {:ok true :world world' :amount amount}.
   Mirrors EscrowVault.withdrawFees.

   Guard: amount must be > 0."
  [world token]
  (let [amount (get-in world [:total-fees token] 0)]
    (if (zero? amount)
      (t/fail :no-fees-to-withdraw)
      (let [world' (assoc-in world [:total-fees token] 0)]
        (assoc (t/ok world') :amount amount)))))

;; ---------------------------------------------------------------------------
;; Claimable balances (push-transfer fallback)
;;
;; When a direct token transfer fails (e.g. recipient is a non-receiving contract),
;; the amount is recorded in claimableBalances[workflowId][addr].
;; The recipient calls withdrawEscrow() to claim it later.
;; ---------------------------------------------------------------------------

(defn record-claimable
  "Record amount as claimable by addr for workflow-id.
   Mirrors: claimableBalances[workflowId][recipient] += amount"
  [world workflow-id addr amount]
  (update-in world [:claimable workflow-id addr] (fnil + 0) amount))

(defn withdraw-escrow
  "Claim claimable balance for addr on workflow-id.
   Mirrors: BaseEscrow.withdrawEscrow.

   Guard: escrow must be in terminal state (:released/:refunded/:resolved).
   Guard: claimable balance must be > 0."
  [world workflow-id addr]
  (cond
    (not (t/valid-workflow-id? world workflow-id))
    (t/fail :invalid-workflow-id)

    (not (t/terminal-state? world workflow-id))
    (t/fail :transfer-not-finalized)

    :else
    (let [amount (get-in world [:claimable workflow-id addr] 0)]
      (if (zero? amount)
        (t/fail :no-claimable-balance)
        (let [world' (assoc-in world [:claimable workflow-id addr] 0)]
          (assoc (t/ok world') :amount amount))))))

;; ---------------------------------------------------------------------------
;; BondCollector appeal bond accounting
;;
;; When an appeal is raised, the appellant posts a bond.
;; Protocol fee is deducted: bond * appeal-bond-protocol-fee-bps / 10000
;; Remainder goes to the incentive module.
;;
;; BondCollector storage (modelled in world):
;;   :bond-balances {workflow-id {addr amount}}   ; posted bonds per escrow/poster
;;   :bond-fees     {token amount}                 ; accumulated protocol fees from bonds
;; ---------------------------------------------------------------------------

(defn post-appeal-bond
  "Record an appeal bond posted by appellant for workflow-id.
   Deducts protocol fee into :bond-fees; records net in :bond-balances.

   snap — ModuleSnapshot map for this workflow (for appeal-bond-protocol-fee-bps)
   token — bond token address
   amount — gross bond amount"
  [world workflow-id appellant snap token amount]
  (let [fee-bps (or (:appeal-bond-protocol-fee-bps snap) 0)
        fee     (t/compute-fee amount fee-bps)
        net     (- amount fee)]
    (-> world
        (update-in [:bond-balances workflow-id appellant] (fnil + 0) net)
        (update-in [:bond-fees token] (fnil + 0) fee))))

(defn slash-bond
  "Slash the posted bond for a losing appellant.
   Moves balance from :bond-balances to :bond-slashed (for incentive distribution).

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)]
    (if (zero? amount)
      (t/fail :no-bond-to-slash)
      (let [world' (-> world
                       (assoc-in [:bond-balances workflow-id appellant] 0)
                       (update-in [:bond-slashed workflow-id] (fnil + 0) amount))]
        (assoc (t/ok world') :slashed amount)))))

(defn return-bond
  "Return the posted bond to a winning appellant.
   Clears :bond-balances entry.

   Guard: bond balance must be > 0."
  [world workflow-id appellant]
  (let [amount (get-in world [:bond-balances workflow-id appellant] 0)]
    (if (zero? amount)
      (t/fail :no-bond-to-return)
      (let [world' (assoc-in world [:bond-balances workflow-id appellant] 0)]
        (assoc (t/ok world') :returned amount)))))
