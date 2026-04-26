# SEW Dispute Resolution Simulator

> Adversarial security testing for Ethereum escrow and dispute-resolution protocols.

## What is this?
A specialized adversarial simulator for **SEW Protocol** protected transfers and decentralized dispute resolution. It models multi-actor, time-ordered failure modes that emerge from the interaction of valid transactions—failures that static analysis and unit tests cannot detect.

## Why it exists?
To verify whether SEW behaves as a **robust game** before real-world deployment. Protocols that move funds through escrow rely on the structural integrity of their resolution logic; this simulator tests that integrity under extreme stress.

## What it verifies
*   **State-Machine Correctness**: Every transition is checked against a formal model of the protocol.
*   **Accounting Reconciliation**: Atomic balance updates are enforced for every state change.
*   **Adversarial Liveness**: Detects conditions where funds become permanently locked due to rational agent withdrawal or capacity exhaustion.
*   **Trace Replay & EVM Equivalence**: Execution traces are replayed step-by-step against live Solidity contracts to ensure the model never drifts from the code.

---

## 🔬 The Phase Z Discovery
During a baseline sweep, the simulator identified a critical **liquidity leak** in the automated timeout logic. The system transitioned to a terminal state but failed to reconcile the underlying protocol balances.

We have published the standardized traces for this discovery to demonstrate the simulator's diagnostic power:
1.  **[Known Failure Trace](examples/cdrs/phase-z-known-failure.trace.json)**: Reproduces the multi-escrow liquidity leak.
2.  **[Fixed Regression Trace](examples/cdrs/phase-z-fixed-regression.trace.json)**: Verifies the fix via the **Reconciliation Mandate**.

---

## 🌐 CDRS v0.1
This project is the first implementation of the **[Common Dispute Resolution Standard (CDRS) v0.1](spec/cdrs-v0.1.md)** — a draft schema for standardized, adversarial trace-testing of decentralized arbitration.

---

## Current Status
*   **Strong**: Transition correctness, 13 core protocol invariants, and Trace Equivalence (Clojure ↔ EVM) are verified and passing.
*   **Ongoing**: Multi-agent equilibrium modeling, parameter sensitivity sweeps, and standard generalization.

---

## Quick Start

### 1. Run the Adversarial Suite
Requires the gRPC state machine server:
```bash
# Start the server
clojure -M:run -- -S --port 7070 &

# Run scenarios
cd python && python invariant_suite.py
```

### 2. Verify Trace Equivalence
Verify model traces against live Solidity:
```bash
# From the sew-protocol repository
forge test --match-test test_trace_equivalence
```

---

## 🔍 Evidence & Scenarios

| Scenario | Status | CDRS Trace |
|----------|--------|----------------|
| **F3 — Governance Sandwich** | ✅ PASS | [Forensic Trace](docs/evidence/detailed/F3-governance-sandwich.md) |
| **F7 — Profit-Threshold Strike** | ✅ PASS | [Forensic Trace](docs/evidence/detailed/F7-profit-threshold-strike.md) |
| **Phase Z — Liveness Failure** | ✅ FIXED | **[Gold Artifact](examples/cdrs/phase-z-fixed-regression.trace.json)** |

See **[Full Scenario Index](docs/scenarios.md)** for all 33 verified protocol properties.
