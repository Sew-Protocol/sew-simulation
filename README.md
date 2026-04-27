# SEW Protocol Validation Suite

Deterministic validation and adversarial testing for escrow and dispute resolution protocols.

## What this is

This is a validation framework for the SEW Protocol, designed to test whether its dispute resolution system remains reliable under real-world conditions.

It models how multiple actors interact over time — including adversarial behaviour — and verifies that the protocol maintains correctness, safety, and liveness.

## Why it exists

Most smart contract testing answers:

“Does this function work?”

This system answers:

“Does the protocol still work when participants behave strategically or adversarially over time?”

For escrow and dispute resolution systems, failures don’t come from invalid code —
they come from valid actions interacting in unexpected sequences.

## What it verifies

- **State Machine Correctness**: Every transition is validated against the protocol model.
- **Invariant Enforcement**: Critical properties (e.g. bond liquidity, withdrawal safety, fee caps, time-locks) are continuously checked.
- **Accounting Integrity**: Funds are conserved and reconciled across all transitions.
- **Adversarial Liveness**: Detects conditions where funds can become stuck due to rational or malicious behaviour.
- **Deterministic Replay**: All scenarios are reproducible and can be replayed step-by-step.
- **Model ↔ EVM Equivalence (ongoing)**: Execution traces are validated against Solidity implementations.

## Key Features

- **Deterministic Fixture System**: Composable scenario suites for regression testing and protocol exploration.
- **Golden Snapshotting**: Detects behavioural drift across protocol changes.
- **Invariant-Driven Testing**: Failures are defined by violated guarantees, not just incorrect outputs.

## Example: Phase Z Discovery

The system identified a critical liquidity reconciliation failure in timeout handling:

- Protocol reached a terminal state
- Underlying balances were not fully reconciled
- Result: hidden loss of funds across multiple escrows

This was reproduced and fixed using deterministic traces:

- Known failure: `examples/cdrs/phase-z-known-failure.trace.json`
- Verified fix: `examples/cdrs/phase-z-fixed-regression.trace.json`

## Current Status

- ✅ Core invariant suite passing
- ✅ Fixture-based scenario system operational
- ✅ Golden snapshot regression framework active
- ✅ Python ↔ gRPC integration validated

## In progress

- Multi-agent adversarial dynamics
- Parameter sensitivity and equilibrium analysis
- Expanded EVM trace equivalence

## Quick Start

### Run validation suite
```bash
# Start simulation engine
clojure -M:run -- -S --port 7070 &

# Run fixture suites
cd python
python invariant_suite.py
```

### Verify Solidity equivalence
```bash
# From sew-protocol repo
forge test --match-test test_trace_equivalence
```

## Documentation
- `fixtures/README.md` — fixture composition and schema
- `docs/testing/` — validation coverage and status
- `docs/scenarios.md` — scenario index and protocol properties

## Positioning

This repository provides:
- A validation layer for the SEW Protocol
- A framework for adversarial protocol testing
- A reference implementation of dispute resolution under real-world conditions
