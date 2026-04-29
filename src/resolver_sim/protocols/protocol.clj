(ns resolver-sim.protocols.protocol
  "DisputeProtocol — the interface that protocol plugins must implement.

   The generic replay engine calls these methods during scenario execution.
   Implementations must be pure (no I/O, no DB) and deterministic.

   Usage:
     Implement DisputeProtocol via deftype or defrecord, then pass an instance
     to replay/replay-with-protocol alongside a scenario map.  The SEW
     implementation lives in resolver-sim.protocols.sew; the Dummy proof-of-
     concept lives in resolver-sim.protocols.dummy.

   Layering: this namespace has no dependencies on contract_model/*, model/*,
   sim/*, db/*, or io/*.  It is a pure interface definition.")

(defprotocol DisputeProtocol
  "Plugin interface for dispute-resolution protocol implementations.

   Implement this to plug a new protocol (SEW, Dummy, UMA-style, etc.) into
   the generic replay engine without modifying replay.clj or any existing
   contract_model/* code."

  (build-execution-context [protocol agents protocol-params]
    "Build an execution context from agent list and protocol parameters.
     agents          — [{:id str :address str :type str ...}]
     protocol-params — map of protocol-specific config (same shape as
                       :protocol-params in a scenario map)
     Returns a context map passed opaquely to every dispatch-action call.
     Must include :agent-index {agent-id → agent} for replay metrics.")

  (dispatch-action [protocol context world event]
    "Apply one event to the world state.
     context — opaque map returned by build-execution-context
     world   — current canonical world state
     event   — {:seq n :time t :agent str :action str :params {...}}
     Returns {:ok bool :world world' :error kw :extra {...}}.
     Must never throw — all error paths must return {:ok false :error kw}.")

  (check-invariants-single [protocol world]
    "Single-world invariant checks (e.g. solvency, non-negative balances).
     Called after every successful transition on the post-transition world.
     Returns {:ok? bool :violations map-or-nil}.")

  (check-invariants-transition [protocol world-before world-after]
    "Cross-world invariant checks (e.g. terminal-state irreversibility).
     Called after every successful transition with both worlds.
     Returns {:ok? bool :violations map-or-nil}.")

  (world-snapshot [protocol world]
    "Create a lean, serializable map of the world state for trace output.
     Should exclude large internal state and keep only relevant metrics."))
