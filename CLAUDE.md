# How to work in this repository

## What this system is

A **statistical simulation** of the SEW decentralised dispute-resolution protocol,
plus a **live contract model** that simulates actual contract execution.

Two distinct modes:
- **Statistical model** (`sim/`, `model/`) — Monte Carlo sweeps over protocol
  parameter space; tests falsifiable hypotheses about resolver incentives.
- **Live simulation** (`contract_model/`) — deterministic execution of SEW
  contract state machines against adversarial strategies; records outcomes to XTDB.

---

## Principles

- Simulation logic is pure; I/O (file and database) is confined to the shell
- Hypotheses are falsifiable — every phase produces a pass/fail result with a
  specific threshold, not a qualitative judgement
- The contract model mirrors the on-chain spec exactly; divergence is a bug
- Params live in EDN files under `params/`; results are written to `results/`

---

## Architecture

### Functional core (no I/O, no DB)

```
contract_model/     ← deterministic contract execution
  state_machine.clj   escrow state transitions
  lifecycle.clj       contract lifecycle (create → dispute → resolve)
  accounting.clj      fee and profit calculations
  resolution.clj      dispute resolution logic
  authority.clj       resolver authority checks
  invariants.clj      post-condition checks
  runner.clj          top-level trial runner (run-trial, run-with-divergence-check)
  types.clj           shared types / constants

model/              ← statistical/economic models (pure functions)
  economics.clj, dispute.clj, decision_quality.clj, ...

sim/                ← simulation phases (pure sweeps over params)
  phase_o.clj ... phase_aa.clj
  adversarial.clj, batch.clj, sweep.clj, waterfall.clj, ...

governance/         ← governance rule models (pure)
adversaries/        ← adversary strategy models (pure)
oracle/             ← detection models (pure)
```

### Imperative shell (I/O)

```
db/                 ← XTDB persistence for live simulation outcomes
  store.clj           sew_trial_outcomes + sew_escrow_events table ops;
                      summarise-batch (pure aggregate helper)
  telemetry.clj       adapter: contract_model/runner output → db writes

io/                 ← file I/O
  params.clj          EDN param loading
  results.clj         result serialisation
```

### Entry point

```
core.clj            ← CLI dispatch; wires all phase flags
```

---

## Layering rules

These must not be violated as the project grows:

| Namespace | May import | Must NOT import |
|---|---|---|
| `contract_model/*` | other `contract_model/*` | `db/*`, `io/*`, `evaluation.*` |
| `model/*` | nothing outside `model/` | everything else |
| `sim/*` | `contract_model/*`, `model/*`, `governance/*`, `adversaries/*`, `oracle/*` | `db/*`, `io/*` |
| `governance/*`, `adversaries/*`, `oracle/*` | `model/*` only | `db/*`, `io/*` |
| `db/*` | `contract_model/*`, `evaluation.xtdb` | `sim/*` |
| `io/*` | `model/*`, `sim/*` | `db/*` |
| `core.clj` | everything | — |

The key invariant: **the functional core is testable without a running XTDB instance
or filesystem.** `db/` and `io/` are the only namespaces with side effects.

---

## Directory structure — current and intended growth

### Current layout

```
src/resolver_sim/
  contract_model/   pure contract mechanics
  model/            pure statistical/economic models  (~10 files)
  sim/              pure simulation phases            (~15 files)
  governance/       pure governance rule models
  adversaries/      pure adversary strategies
  oracle/           pure detection models
  db/               shell: XTDB persistence
    store.clj         resolver-sim.db.store
    telemetry.clj     resolver-sim.db.telemetry
  io/               shell: file I/O
    params.clj
    results.clj
  core.clj
```

### Growth triggers and responses

**`sim/` reaches ~25 files** — group by test domain:
```
sim/
  economic/         phases testing fee/profit/incentive hypotheses
  governance/       phases testing governance capture, rule drift
  adversarial/      phases testing attack strategies
  adversarial.clj   (existing top-level, keep or absorb)
  batch.clj, sweep.clj, waterfall.clj
```

**`model/` develops two distinct sub-domains** — e.g. economic models vs.
adversarial models — split into `model/economic/` and `model/adversarial/`.
Do not split until the sub-domain boundary is clear; a flat `model/` is correct
for ≤15 files.

**New XTDB tables** (governance events, metrics snapshots, etc.) — add new files
in `db/`, e.g. `db/governance.clj`. Do not extend `db/store.clj` indefinitely;
split by table group when `store.clj` exceeds ~200 lines of query logic.

**`contract_model/` grows for live simulation** — if the live contract model
expands to cover multi-party workflows, split by workflow type:
```
contract_model/
  escrow/           single-escrow lifecycle (current)
  multi_party/      future: panel workflows, ring contracts
  shared/           types, invariants, accounting (shared across workflows)
```

---

## Cross-project coupling (eval-engine)

sew-simulation depends on eval-engine as a local dep:
```clojure
og/eval-engine {:local/root "../og/eval-engine"}
```

**Only `resolver-sim.db.*` may import `evaluation.xtdb`** (or its future home
`evaluation.db.xtdb` when eval-engine restructures its own shell layer).

This import must never appear in `contract_model/`, `sim/`, `model/`, or any
other core namespace.

When eval-engine moves `xtdb.clj` → `evaluation/db/xtdb.clj`, update:
- `resolver-sim.db.store` require: `evaluation.xtdb` → `evaluation.db.xtdb`

---

## File map

| File | Role |
|---|---|
| `core.clj` | CLI entry point; dispatch only, no logic |
| `contract_model/runner.clj` | Top-level trial runner; entry point for live sim |
| `contract_model/state_machine.clj` | Escrow state transitions |
| `contract_model/invariants.clj` | Post-condition checks; add new invariants here |
| `db/store.clj` | XTDB table ops + `summarise-batch` (pure) |
| `db/telemetry.clj` | Adapter: runner output → db writes |
| `io/params.clj` | EDN param loading |
| `io/results.clj` | Result serialisation |
| `sim/phase_*.clj` | One file per simulation phase; entry point `run-phase-*-sweep` |
| `contract_model/invariant_scenarios.clj` | S01–S23 deterministic scenarios as Clojure data |
| `contract_model/invariant_runner.clj` | In-process runner; `run-all` / `print-report` / `run-and-report` |

---

## Params and results

- Params files live in `params/` as EDN; must include full schema (use
  `params/phase-o-baseline.edn` as the canonical template)
- Results are written to `results/` (gitignored; use `git add -f` to force-track)
- Docs live in `docs/results/` (also gitignored; force-add when recording findings)

---

## Testing

- **Deterministic invariant suite** (S01–S23, 23 scenarios): `clojure -M:run -- --invariants` (in-process, no gRPC required; ~0.1 s)
- **Adversarial failure-mode suite** (S24–S33, 10 scenarios): `python python/invariant_suite.py` (requires gRPC server on :7070)
- **gRPC server**: `nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &`
- **Monte Carlo phases** (G–AD): `scripts/monte-carlo/test-all.sh`
- **Single phase**: `clojure -M:run -- -p params/<phase>.edn <flags>`
- **Unit tests**: `clojure -M:test -e "(require '...)(clojure.test/run-tests '...)"`
- Integration tests (`db/telemetry_integration_test.clj`) require XTDB on localhost:5432
- Pass threshold for most hypotheses: **≥80% across all scenarios in a sweep**
