(ns resolver-sim.protocols.dummy
  "DummyProtocol — minimal always-pass DisputeProtocol implementation.

   Proves that the replay engine can run non-SEW protocols without any
   modification to existing contract_model/* code.

   Behaviour:
   - create_escrow: assigns a sequential workflow-id so :save-wf-as aliases
     resolve correctly in subsequent events.
   - All other actions: succeed without modifying world state.
   - Invariant checks: always pass (no invariants enforced).

   This is a proof-of-concept, not a useful protocol.  It demonstrates that
   replay-with-protocol's generic machinery (alias resolution, metrics, trace
   shape) works independently of SEW semantics.

   Layering: may import protocols/protocol only.
   Must NOT import contract_model/*, model/*, db/*, io/*."
  (:require [resolver-sim.protocols.protocol :as proto]))

;; ---------------------------------------------------------------------------
;; DummyProtocol implementation
;; ---------------------------------------------------------------------------

(deftype DummyProtocol []
  proto/DisputeProtocol

  (build-execution-context [_ agents _protocol-params]
    {:agent-index (into {} (map (juxt :id identity) agents))})

  (dispatch-action [_ _context world event]
    (if (= "create_escrow" (:action event))
      ;; Assign the next sequential workflow-id so :save-wf-as aliases resolve.
      ;; Include :token and :amount-after-fee so the projection-hash accounting
      ;; invariant (called unconditionally by the replay engine) does not NPE.
      (let [wf-id  (count (:escrow-transfers world))
            token  (get-in event [:params :token] "DUMMY")
            amount (get-in event [:params :amount] 0)]
        {:ok    true
         :world (assoc-in world [:escrow-transfers wf-id]
                          {:escrow-state    :pending
                           :token           token
                           :amount-after-fee amount})
         :extra {:workflow-id wf-id}})
      {:ok true :world world}))

  (check-invariants-single [_ _world]
    {:ok? true :violations nil})

  (check-invariants-transition [_ _world-before _world-after]
    {:ok? true :violations nil})

  (world-snapshot [_ world]
    {:block-time (:block-time world)})

  (init-world [_ scenario]
    {:escrow-transfers {}
     :block-time (get scenario :initial-block-time 1000)})

  (compute-projection [_ _world]
    [nil nil])

  (classify-transition [_ _action _result-kw]
    nil))

;; ---------------------------------------------------------------------------
;; Shared singleton
;; ---------------------------------------------------------------------------

(def protocol
  "Ready-made DummyProtocol instance."
  (DummyProtocol.))
