# SEW Dispute Resolution Simulator

> **Adversarial security testing for Ethereum escrow and dispute-resolution protocols.**
> Finds failures that Slither, Echidna, and Foundry fuzzing cannot — because they only emerge from the interaction of multiple valid transactions across multiple actors.

---

## Three Simulation Layers

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
