(ns resolver-sim.protocols.sew
  "SEWProtocol — DisputeProtocol implementation for the SEW contract model.

   Wraps the existing contract_model/* functions without modifying them.
   All dispatch and invariant checking delegates to the replay engine's
   established SEW multimethod and invariant check functions.

   Usage:
     (replay/replay-with-protocol sew/protocol <scenario>)

   Layering: may import contract_model/* and engine/*.
   Must NOT import sim/*, db/*, io/*."
  (:require [resolver-sim.engine.protocol         :as engine]
            [resolver-sim.contract-model.replay   :as replay]))

;; ---------------------------------------------------------------------------
;; SEWProtocol implementation
;; ---------------------------------------------------------------------------

(deftype SEWProtocol []
  engine/DisputeProtocol

  (build-execution-context [_ agents protocol-params]
    ;; Delegates to the existing build-context function in replay.clj,
    ;; which wires up the ModuleSnapshot, resolution-module-fn, escalation-fn,
    ;; and agent-index exactly as replay-scenario does.
    (replay/build-context agents protocol-params))

  (dispatch-action [_ context world event]
    ;; Delegates to the existing apply-action multimethod via its public wrapper.
    ;; All SEW-specific action semantics (lifecycle, resolution, authority) are
    ;; unchanged — this adapter is purely a forwarding layer.
    (replay/sew-dispatch-action context world event))

  (check-invariants-single [_ world]
    (replay/sew-check-invariants-single world))

  (check-invariants-transition [_ world-before world-after]
    (replay/sew-check-invariants-transition world-before world-after)))

;; ---------------------------------------------------------------------------
;; Shared singleton
;; ---------------------------------------------------------------------------

(def protocol
  "Ready-made SEWProtocol instance.
   Pass to replay/replay-with-protocol or run-suite-with-protocol."
  (SEWProtocol.))
