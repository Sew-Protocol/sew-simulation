# SEW Dispute Resolution Simulator

> When funds move through an Ethereum protocol, you're trusting that disputes will be resolved fairly. This project tests whether that trust holds — and finds the failure modes no existing tool can detect.

> **Adversarial security testing for Ethereum escrow and dispute-resolution protocols.**
> Finds failures that Slither, Echidna, and Foundry fuzzing cannot — because they only emerge from the interaction of multiple valid transactions across multiple actors.

[![33/33 scenarios passing](https://img.shields.io/badge/scenarios-33%2F33%20passing-brightgreen)](#all-33-scenarios)
[![0 invariant violations](https://img.shields.io/badge/invariant%20violations-0-brightgreen)](src/resolver_sim/contract_model/invariants.clj)

---

## The Problem

Most smart contract security tools analyse one function at a time. They ask: *"is this call valid?"*

The most dangerous escrow and dispute-resolution failures are not in any single function. They arise from sequences of valid calls across multiple actors:

- A governance rotation that happens *while a dispute is open*
- A resolver flood that *exhausts capacity* before disputes time out
- An escalation that *resets finality* via MEV ordering advantage

These failures are invisible to static analysis. They require simulating multiple actors, acting in sequence, across real protocol state. That is what this simulator does.

---

## What This Does

The simulator executes **named adversarial scenarios** against a live implementation of the SEW contract state machine. At every state transition, **13 protocol invariants** are checked. If any invariant is violated, the scenario fails immediately with a full trace.

It operates at three layers:

| Layer | What it tests | Status |
|-------|--------------|--------|
| **Adversarial invariant suite** | 33 named attack scenarios against the live state machine | ✅ 33/33 passing |
| **Contract model + gRPC server** | Deterministic escrow lifecycle, DR1/DR2/DR3 escalation, invariant checking | ✅ Complete |
| **Monte Carlo statistical simulation** | Resolver incentive economics across 30+ parameter sweep phases | ✅ Complete |
| **Foundry differential testing** | EVM vs model divergence (deploy real Solidity, compare step-by-step) | 🔧 In progress |

---

## Three Failure Classes Detected

### 🏛 Governance Sandwich (`F3`)

A governance actor rotates the authorized resolver *after* a dispute opens but *before* it resolves. The replacement resolver settles the in-flight dispute in the attacker's favour. Each step — rotation and resolution — is individually authorized. The attack is invisible to per-function analysis.

**Result:** Dispute outcome retroactively altered by a governance action the original parties could not anticipate.

### 💸 Profit-Threshold Strike (`F7`)

A resolver with a minimum profit threshold refuses to service escrows where the protocol fee falls below its cost floor. The dispute runs to expiry with zero resolutions. No on-chain error is raised.

**Result:** Funds permanently locked in a dispute with no on-chain signal and no detectable fault. Only economic agent simulation catches it.

### 🌊 Cascade Escalation Drain (`F10`)

Four buyers simultaneously raise disputes against a single arbitrator capped at two resolutions. Two escrows resolve; two are permanently stuck in `disputed` state. No invariant is violated — capacity exhaustion is not a protocol bug. But the funds are inaccessible.

**Result:** A coordinated dispute flood locks user funds at negligible attacker cost. The failure only manifests under concurrent load simulation.

---

## 🔍 Evidence

See **[Adversarial Simulation Evidence](docs/evidence/summary.md)** — reproducible outputs for all 10 Ethereum failure mode scenarios, with raw JSON.

- [F3 — Governance Sandwich](docs/evidence/detailed/F3-governance-sandwich.md): retroactive outcome manipulation via resolver rotation
- [F7 — Profit-Threshold Strike](docs/evidence/detailed/F7-profit-threshold-strike.md): economic liveness failure from rational resolver withdrawal  
- [F10 — Cascade Escalation Drain](docs/evidence/detailed/F10-cascade-escalation.md): system-level DoS from capacity exhaustion

---

## Quick Start

**Requirements:** Java 11+, [Clojure CLI](https://clojure.org/guides/install_clojure), Python 3.10+

```bash
git clone https://github.com/your-org/sew-simulation
cd sew-simulation

# Install Python dependencies
pip install -e python/

# Start the Clojure gRPC state machine server
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
sleep 8   # wait for JVM startup

# Run all 33 adversarial scenarios (~1 second)
cd python && python invariant_suite.py

# Run a single scenario
python invariant_suite.py --scenario F3

# Write a JSON report
python invariant_suite.py --json results/run.json
```

---

## Example Output

```
════════════════════════════════════════════════════════════════════════
  SEW Invariant Suite — Adversarial Security Scenarios
════════════════════════════════════════════════════════════════════════
  git:     7e98fc1  (ethereum)
  python:  3.12.10
  run at:  2026-04-15T17:13:50Z
  mode:    deterministic (no randomness; seed: n/a)
────────────────────────────────────────────────────────────────────────
  Scenario                                       time  steps  reverts  status
  ──────────────────────────────────────────────────────────────────────
  ✓ PASS  S01  baseline-happy-path                       0.0s  steps=2     reverts=0
  ✓ PASS  S07  unauthorized-resolver-rejected            0.0s  steps=4     reverts=1
  ✓ PASS  S08  state-machine-attack-gauntlet             0.0s  steps=9     reverts=7
  ✓ PASS  S22  status-leak-agree-cancel-over-dispute     0.0s  steps=3     reverts=0
  ✓ PASS  S26  f3-governance-sandwich                         steps=6   attacks=4   successes=2
               rotations=1  malicious_resolutions_attempted=3  malicious_resolutions_succeeded=1
  ✓ PASS  S27  f4-escalation-loop-amplified                   steps=60  attacks=14  amplification=14.00x
  ✓ PASS  S33  f10-cascade-escalation-drain                   disputes=4  arbitrator.resolutions=2  still_disputed=2

════════════════════════════════════════════════════════════════════════
  33/33 scenarios passed  (0.6s total)
────────────────────────────────────────────────────────────────────────
  Summary statistics
  Total transactions processed:               327
  Rejected (reverts):                         189  (57.8%)
  Escrows created:                             43
  Total escrow volume simulated:           112,701
  Disputes triggered:                          32
  Resolutions executed:                        27
  Attack attempts logged:                      41
  Attack successes:                             9  (22.0%)
  Invariant violations:                         0
════════════════════════════════════════════════════════════════════════
```

Each run is stamped with git SHA, Python version, and UTC timestamp — fully reproducible.

---

## Why This Matters for Ethereum

Every protocol that moves funds through escrow — DEX settlement, DAO treasury, cross-chain bridges, grant disbursement — relies on dispute resolution at some layer. The failure modes this simulator tests are **not SEW-specific**. They are structural risks in any protocol that combines:

- Time-bounded dispute windows
- Delegated resolver authority
- Multi-level escalation
- Governance-controlled resolver rotation

Publishing this tooling means the next team building an escrow layer starts with a proven adversarial test suite, not a blank page.

---

## All 33 Scenarios

**S01–S23: Protocol correctness**

Baseline lifecycle, dispute release/refund, timeout autocancellation, mutual cancel, unauthorized resolver rejection, state machine attack gauntlet, multi-escrow solvency, double-finalize rejection, DR3/Kleros escalation, governance isolation, status-leak regression, concurrent state races.

**S24–S33: Ethereum failure modes (F1–F10)**

| ID | Scenario | Failure class |
|----|----------|--------------|
| F1 | Liveness extraction | Dispute flood / capacity exhaustion |
| F2 | Appeal window race | MEV front-run resets finality |
| F3 | Governance sandwich | Resolver rotation mid-dispute |
| F4 | Escalation loop amplification | Zero-cost gas griefing |
| F5 | Concurrent status desync | Cancel + dispute ordering race |
| F6 | Resolver cartel | Full escalation capture |
| F7 | Profit-threshold strike | Rational resolver withdrawal |
| F8 | Appeal fee amplification | Multi-level cost griefing |
| F9 | Sub-threshold misresolution | Fraudulent L0, corrected at L1 |
| F10 | Cascade escalation drain | Capacity-limited arbitrator flood |

See [`docs/scenarios.md`](docs/scenarios.md) for full descriptions.

---

## Monte Carlo Results (Layer 3)

30+ parameter sweep phases (G–AD) validate resolver incentive economics across fee structures, bond mechanics, governance decay, and multi-epoch behaviour:

| Strategy | Avg profit | vs Honest |
|----------|-----------|-----------|
| Honest | 150.00 | — |
| Lazy | 137.75 | −8.2% |
| Collusive | 134.25 | −10.5% |
| **Malicious** | **32.75** | **−78.2%** |

Honest participation earns **3–5× more than malicious strategies** across all tested configurations.

---

## Repository Structure

```
python/                     Adversarial invariant suite
  invariant_suite.py          ← main: run all 33 scenarios
  eth_failure_modes.py        F1–F5 Ethereum failure mode scenarios
  eth_failure_modes_2.py      F6–F10 Ethereum failure mode scenarios
  sew_sim/
    live_runner.py            gRPC-backed agent event loop
    live_agents.py            adversarial agent implementations
    anvil_runner.py           Foundry/Anvil differential harness (in progress)

src/resolver_sim/           Clojure contract model + gRPC server
  contract_model/
    state_machine.clj         escrow state transitions
    invariants.clj            13 protocol invariants
    resolution.clj            DR1/DR2/DR3 dispute resolution
    runner.clj                trial runner
  server/                     gRPC session server
  sim/                        Monte Carlo phases G–AD
  model/                      statistical/economic models
  governance/                 governance rule models
  adversaries/                adversary strategy models
  db/                         XTDB persistence
  core.clj                    CLI entry point

scripts/monte-carlo/        Monte Carlo run/test scripts
params/                     EDN parameter files
docs/                       Architecture, scenario index, usage guides
```

---

## Documentation

- [`docs/overview.md`](docs/overview.md) — what this is and why it exists
- [`docs/scenarios.md`](docs/scenarios.md) — complete scenario index with descriptions
- [`docs/architecture.md`](docs/architecture.md) — how the simulator works internally
- [`docs/usage.md`](docs/usage.md) — how to run simulations and interpret output
- [`docs/security-model.md`](docs/security-model.md) — failure classes tested and why they matter

---

## Design Principles

- **Simulation logic is pure** — I/O and database access are confined to `db/` and `io/`; the functional core is testable without a running database
- **Hypotheses are falsifiable** — every phase produces pass/fail against a specific threshold (≥80%), not a qualitative judgement
- **Deterministic** — same inputs produce identical output; git SHA and timestamp are stamped on every run
- **The contract model mirrors the spec exactly** — divergence between the Clojure model and the on-chain contract is a bug, not a warning

---

## Grant Application

This project is submitted to the Ethereum security grant programme as open-source security infrastructure for escrow and dispute-resolution protocols.

[Preventing Hidden Failures in Ethereum Protocols](https://giveth.io/project/preventing-hidden-failures-in-ethereum-protocols) — Giveth

### 1 — Adversarial Invariant Suite (Python + Clojure gRPC)

**What it does:** Executes deterministic, named adversarial scenarios against a live Clojure contract state machine over gRPC. Checks 13 protocol invariants at every state transition.

**Why it matters:** Static analysis (Slither, Echidna, Foundry fuzz) cannot model multi-actor, time-ordered failures. This suite explicitly tests failure classes that only emerge from the interaction of two or more valid state transitions.

**Current coverage:** 33 scenarios — S01–S23 (protocol properties) + F1–F10 (Ethereum failure modes)

```
F1  Resolver liveness extraction (dispute flood / capacity exhaustion)
F2  Appeal window race (MEV front-run resets finality)
F3  Governance sandwich (resolver rotation mid-dispute)
F4  Escalation loop amplification (zero-cost gas griefing)
F5  Concurrent status desync (cancel + dispute ordering bug)
F6  Resolver cartel (full escalation capture)
F7  Profit-threshold strike (rational resolver withdrawal)
F8  Appeal fee amplification (multi-level cost griefing)
F9  Sub-threshold misresolution (fraudulent L0, corrected at L1)
F10 Cascade escalation drain (capacity-limited arbitrator)
```

**Run:**
```bash
# Start the gRPC server (Clojure)
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &

# Run all 33 scenarios (~0.6s)
cd python && python invariant_suite.py

# Run a single scenario
python invariant_suite.py --scenario F3

# JSON report
python invariant_suite.py --json results/run.json
```

Output includes a reproducibility header (git SHA, branch, UTC timestamp) and per-scenario timing.

---

### 2 — Clojure Contract Model (State Machine + gRPC Server)

**What it does:** Deterministic execution of the SEW contract state machine — escrow lifecycle, resolver authority, DR1/DR2/DR3 escalation tiers, governance rotation. Exposes a gRPC session API for the Python adversarial layer (Layer 1) and the Foundry differential harness (Layer 3).

**Structure:**
```
src/resolver_sim/
  contract_model/
    state_machine.clj   — escrow state transitions
    lifecycle.clj       — create → dispute → resolve
    invariants.clj      — 13 post-condition checks
    resolution.clj      — DR1/DR2/DR3 dispute resolution
    authority.clj       — resolver authority checks
    accounting.clj      — fee and profit calculations
    runner.clj          — top-level trial runner
  server/               — gRPC session server
  db/                   — XTDB persistence (outcomes + events)
```

**Run the gRPC server:**
```bash
clojure -M:run -- -S --port 7070
```

---

### 3 — Foundry Differential Testing (Next Milestone)

**What it does:** Deploys the full EscrowVault + DR module stack to a local Anvil instance via a Forge script, then drives the simulation using `cast send` / `cast call`. Every state transition is compared step-by-step against the Clojure contract model's `:projection` — any divergence between the EVM and the model is a bug.

**Why it matters:** Closes the gap between the model and the on-chain implementation. A divergence that passes all invariants in the Clojure model but fails on the EVM catches implementation bugs that no amount of model testing can find.

**Status:** Harness implemented (`python/sew_sim/anvil_runner.py`); integration with CI and full scenario coverage in progress.

---

### 4 — Monte Carlo Statistical Simulation (Clojure)

**What it does:** Parameter sweeps over resolver incentive economics. Tests falsifiable hypotheses about fee structures, bond mechanics, slashing parameters, governance decay, and multi-epoch behaviour. 30+ named phases (G through AD).

**Key finding:** Honest resolver participation earns 3–5× more profit than malicious strategies across all tested parameter configurations.

**Run:**
```bash
scripts/monte-carlo/run-dr3.sh          # Full DR3 system
scripts/monte-carlo/test-all.sh         # All 30+ phases (G–AD)
scripts/monte-carlo/run-adversarial.sh  # Adversarial parameter search
```

---

## Repository Structure

```
python/                   Layer 1 — adversarial invariant suite
  invariant_suite.py        main test harness (33 scenarios)
  eth_failure_modes.py      F1–F5 Ethereum failure mode scenarios
  eth_failure_modes_2.py    F6–F10 Ethereum failure mode scenarios
  sew_sim/
    live_runner.py          gRPC-backed agent event loop
    anvil_runner.py         Layer 3 — Foundry/Anvil differential harness

src/resolver_sim/         Layer 2 — Clojure contract model + gRPC
  contract_model/           pure state machine (no I/O)
  server/                   gRPC server
  sim/                      Layer 4 — Monte Carlo phases
  model/                    statistical/economic models
  governance/               governance rule models
  adversaries/              adversary strategy models
  db/                       XTDB persistence (shell layer)
  io/                       file I/O (shell layer)
  core.clj                  CLI entry point

scripts/monte-carlo/      Layer 4 run/test scripts
params/                   EDN parameter files for all phases
results/                  Simulation output (gitignored)
docs/                     Phase findings and architecture docs
```

---

## Quick Start

```bash
# Layer 1 (adversarial suite) — requires gRPC server running
nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &
cd python && python invariant_suite.py

# Layer 4 (Monte Carlo) — standalone
scripts/monte-carlo/run-dr3.sh
```

---

## Design Principles

- **Simulation logic is pure** — I/O and database access are confined to `db/` and `io/`
- **Hypotheses are falsifiable** — every phase produces a pass/fail result against a specific threshold (≥80% by default), not a qualitative judgement
- **The contract model mirrors the on-chain spec exactly** — divergence is a bug
- **The functional core is testable without a running XTDB instance** — `db/` and `io/` are the only namespaces with side effects

---

## Grant Application

This project is submitted to the Ethereum security grant programme as critical security infrastructure for escrow and dispute-resolution protocols.

Relevant write-ups:
- `docs/giveth.md` — grant application link
- Evidence highlights: F3 (governance), F7 (economic), F10 (liveness)
