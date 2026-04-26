(ns resolver-sim.contract-model.registry
  "Resolver registration and staking registry for the SEW protocol.
   Supports Phase K (Tiered Authority) by tracking resolver 'Skin in the Game'.

   Key concepts:
     - Resolver Stake: Amount of capital a resolver has locked in the protocol.
     - Escrow Cap: The maximum escrow amount a resolver is permitted to handle.
     - Slash Capacity: The amount a resolver can be slashed before being disabled.

   Constraints:
     - MAX_SLASH_PER_OFFENSE = 50%
     - RESOLVER_SLASH_CAP_BPS = 20% (per epoch/period)"
  (:require [resolver-sim.contract-model.types      :as t]
            [resolver-sim.contract-model.accounting :as acct]
            [resolver-sim.economics.payoffs            :as payoffs]))

;; ---------------------------------------------------------------------------
;; Stake management
;; ---------------------------------------------------------------------------

(defn register-stake
  "Deposit stake for a resolver address.
   Returns updated world."
  [world resolver-addr amount]
  (update-in world [:resolver-stakes resolver-addr] (fnil + 0) amount))

(defn get-stake
  "Get the current stake for a resolver address."
  [world resolver-addr]
  (get-in world [:resolver-stakes resolver-addr] 0))

(defn can-handle-escrow?
  "True if the resolver's stake is sufficient for the given escrow amount."
  [world resolver-addr escrow-amount]
  (let [stake (get-stake world resolver-addr)
        cap   (payoffs/calculate-escrow-cap stake)]
    (>= cap escrow-amount)))

;; ---------------------------------------------------------------------------
;; Slashing
;; ---------------------------------------------------------------------------

(defn slash-resolver-stake
  "Slash a portion of a resolver's stake.
   Returns updated world with the slashed amount removed from registry
   and distributed according to protocol rules.
   
   Matches DR3 slashing distribution (50/30/20).
   Supports optional challenger bounty for Phase L."
  ([world resolver-addr amount] (slash-resolver-stake world resolver-addr amount nil 0))
  ([world resolver-addr amount challenger bounty-bps]
   (let [current (get-stake world resolver-addr)
         actual  (min current amount)
         world'  (-> (update-in world [:resolver-stakes resolver-addr] - actual)
                     (acct/distribute-slashed-funds actual challenger bounty-bps))]
     (assoc (t/ok world') :slashed-from-stake actual))))
