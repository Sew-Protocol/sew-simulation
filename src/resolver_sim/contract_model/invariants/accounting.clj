(ns resolver-sim.contract-model.invariants.accounting
  "Global accounting consistency invariant: 
   Sum(total-held) + Sum(fees) + Sum(bonds) == Sum(escrow-transfers amount-after-fee)
   
   Ensures no liquidity is leaked or created during any state transition."
  (:require [resolver-sim.contract-model.types :as t]))

(defn- sum-tokens [tokens-map]
  (apply + (vals tokens-map)))

(defn accounting-consistent?
  "Checks if the world state accounting is balanced."
  [world]
  (let [held      (:total-held world {})
        fees      (:total-fees world {})
        bonds     (:bond-balances world {})
        total-escrow (reduce (fn [acc [wf et]]
                               (if (contains? #{:pending :disputed} (:escrow-state et))
                                 (update acc (:token et) (fnil + 0) (:amount-after-fee et))
                                 acc))
                             {}
                             (:escrow-transfers world))
        
        held-sum  (sum-tokens held)
        fee-sum   (sum-tokens fees)
        bond-sum  (reduce (fn [acc m] (+ acc (sum-tokens m))) 0 (vals bonds))
        escrow-sum (sum-tokens total-escrow)]
    
    ;; Invariant: total-held[token] should equal sum of pending/disputed escrows 
    ;; plus any fees/bonds held in the vault.
    (= held-sum (+ escrow-sum fee-sum bond-sum))))
