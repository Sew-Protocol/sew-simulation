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

- Known failure: `data/fixtures/traces/regression/phase-z-known-failure.trace.json`
- Verified fix: `data/fixtures/traces/regression/phase-z-fixed-regression.trace.json`

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

### Run invariant validation suite (fast, in-process)
```bash
clojure -M:run -- --invariants
```

### Run fixture suites (deterministic scenarios)
```bash
clojure -M -e "(require '[resolver-sim.sim.fixtures :as f])(f/run-suite :suites/all-invariants)"
```

### Run Python adversarial failure-mode suite
```bash
# Start gRPC simulation server
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &

# Run suite
python3 python/invariant_suite.py
```

### Adversarial strategy presets (stake-withdraw / fraud discovery)

These presets are tuned for **attack discovery throughput** rather than minimizing rejected actions.
They use effective-step budgeting so failed probes do not prematurely end a run.

#### Broad discovery preset
```bash
python3 python/adversarial_agent.py \
  --target-effective-steps 40 \
  --max-attempts 300 \
  --guided-ratio 0.65 \
  --rejected-step-weight 0.10
```

#### Deeper exploit-chain preset
```bash
python3 python/adversarial_agent.py \
  --target-effective-steps 80 \
  --max-attempts 800 \
  --guided-ratio 0.80 \
  --rejected-step-weight 0.05
```

Tip: for reproducible runs, add `--seed <int>`.

Convenience aliases are also available via `bb`:

```bash
# Start/verify gRPC server on :50051
bb adv:server

# Presets
bb adv:broad
bb adv:deep

# One-shot helpers (start server, then run preset)
bb adv:all:broad
bb adv:all:deep
```

### Verify Solidity equivalence
```bash
# From sew-protocol repo
forge test --match-test test_trace_equivalence
```

## Documentation
- `data/fixtures/README.md` — fixture composition and schema
- `docs/testing/` — validation coverage and status
- `docs/scenarios.md` — scenario index and protocol properties

## Positioning

This repository provides:
- A validation layer for the SEW Protocol
- A framework for adversarial protocol testing
- A reference implementation of dispute resolution under real-world conditions
