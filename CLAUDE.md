# How to work in this repository

## What this system is

A **statistical simulation** of the SEW decentralised dispute-resolution protocol,
plus a **live contract model** that simulates actual contract execution.

Two distinct modes:
- **Statistical model** (`sim/`, `stochastic/`) — Monte Carlo sweeps over protocol
  parameter space; tests falsifiable hypotheses about resolver incentives.
- **Live simulation** (`contract_model/`) — deterministic execution of SEW
  contract state machines against adversarial strategies; records outcomes to XTDB.

---

## Principles

- Simulation logic is pure; I/O (file and database) is confined to the shell
- Hypotheses are falsifiable — every phase produces a pass/fail result with a
  specific threshold, not a qualitative judgement
- The contract model mirrors the on-chain spec exactly; divergence is a bug
- Params live in EDN files under `data/params/`; results are written to `results/`

---

## Architecture

### Functional core (no I/O, no DB)

```
contract_model/     ← deterministic contract execution (SEW state machine)
  state_machine.clj   escrow state transitions
  lifecycle.clj       contract lifecycle (create → dispute → resolve)
  accounting.clj      fee and profit calculations
  resolution.clj      dispute resolution logic
  authority.clj       resolver authority checks
  invariants.clj      post-condition checks (30+ invariants)
  runner.clj          top-level trial runner (run-trial, run-with-divergence-check)
  replay.clj          open-world scenario replay engine; also exposes
                        replay-with-protocol for pluggable protocol implementations
  types.clj           shared types / constants
  trace_metadata.clj  canonical type vocabulary (actors, transitions, effects, outcomes)
  invariant_scenarios.clj  S01–S41 deterministic scenarios as Clojure data
  invariant_runner.clj     in-process runner; run-all / print-report / run-and-report

protocols/          ← pluggable protocol interface + implementations
  protocol.clj        DisputeProtocol interface (pure; no deps)
  sew.clj             SEWProtocol — wraps contract_model/* for use with replay-with-protocol
  dummy.clj           DummyProtocol — always-pass proof-of-concept

stochastic/              ← statistical/economic models (pure functions)
  economics.clj, dispute.clj, decision_quality.clj, ...

sim/                ← simulation phases (pure sweeps over params)
  phase_o.clj ... phase_ai.clj   one file per Monte Carlo phase
  engine.clj          phase harness: make-result, run-parameter-sweep, print-phase-header
  batch.clj           aggregate N trials into summary statistics
  sweep.clj           parameter-space sweep runner
  fixtures.clj        fixture-based suite runner (run-suite, list-suites, minimise-suite)
  minimizer.clj       trace minimisation (reduce failing trace to 1-minimal subset)
  trajectory.clj      equity/spread/displacement trajectory helpers
  multi_epoch.clj     multi-epoch reputation simulation (Phase J)
  adversarial.clj, waterfall.clj, ...

governance/         ← governance rule models (pure)
adversaries/        ← adversary strategy models (pure)
  strategy.clj        Adversary protocol definition
  ring_attacker.clj   RingAttack adversary for multi-epoch trajectory analysis
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
  trace_store.clj     trace persistence
  trace_export.clj    trace export helpers
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
| `protocols/protocol.clj` | nothing | everything else |
| `contract_model/*` | other `contract_model/*`, `protocols/protocol` | `db/*`, `io/*`, `evaluation.*` |
| `protocols/sew` | `contract_model/*`, `protocols/protocol` | `sim/*`, `db/*`, `io/*` |
| `protocols/dummy` | `protocols/protocol` | everything else |
| `stochastic/*` | nothing outside `stochastic/` | everything else |
| `sim/*` | `contract_model/*`, `stochastic/*`, `governance/*`, `adversaries/*`, `oracle/*` | `db/*`, `io/*` |
| `governance/*`, `adversaries/*`, `oracle/*` | `stochastic/*` only | `db/*`, `io/*` |
| `db/*` | `contract_model/*`, `evaluation.xtdb` | `sim/*` |
| `io/*` | `stochastic/*`, `sim/*` | `db/*` |
| `core.clj` | everything | — |

The key invariant: **the functional core is testable without a running XTDB instance
or filesystem.** `db/` and `io/` are the only namespaces with side effects.

> **`sim/engine.clj` vs `protocols/`** — `sim/engine.clj` is the *phase harness*
> (run-parameter-sweep, make-result, print-phase-header).  `protocols/` is the
> *protocol abstraction layer* (DisputeProtocol interface + SEW/Dummy implementations).
> These are unrelated; the similar-sounding names are coincidental.

---

## Directory structure — current and intended growth

### Current layout

```
src/resolver_sim/         ← Clojure namespace root (resolver-sim.*)
  contract_model/         pure contract mechanics (~15 files)
  protocols/              DisputeProtocol interface + SEW/Dummy implementations
  stochastic/                  pure statistical/economic models (~17 files)
  sim/                    simulation phases + phase infrastructure (~38 files)
  governance/             pure governance rule models
  adversaries/            adversary strategy models
  oracle/                 detection models
  economics/              canonical payoff calculations
  canonical/              canonical action vocabulary
  db/                     shell: XTDB persistence
    store.clj               resolver-sim.db.store
    telemetry.clj           resolver-sim.db.telemetry
  io/                     shell: file I/O
    params.clj
    results.clj
  server/                 gRPC server + session management
  core.clj

data/                     ← Static and simulation data
  params/                 Monte Carlo parameter definitions (EDN)
  fixtures/               Deterministic test scenarios and suites (EDN/JSON)
    traces/               Execution traces for replay and regression
```

> **Why `resolver_sim/sim/`?** The outer `resolver_sim/` is the Clojure toolchain
> convention for the `resolver-sim` package root — it cannot be renamed without
> breaking the build.  The inner `sim/` is the `resolver-sim.sim` sub-namespace
> (simulation phases).  They are unrelated despite the similar names.

### Growth triggers and responses

**`sim/` reaches ~50 files** — group by test domain:
```
sim/
  economic/         phases testing fee/profit/incentive hypotheses
  governance/       phases testing governance capture, rule drift
  adversarial/      phases testing attack strategies
  batch.clj, sweep.clj, waterfall.clj, engine.clj   (shared infrastructure)
```

**`stochastic/` develops two distinct sub-domains** — e.g. economic models vs.
adversarial models — split into `stochastic/economic/` and `stochastic/adversarial/`.
Do not split until the sub-domain boundary is clear; a flat `stochastic/` is correct
for ≤20 files.

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

This import must never appear in `contract_model/`, `sim/`, `stochastic/`, or any
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
| `contract_model/replay.clj` | Open-world scenario replay; `replay-scenario` + `replay-with-protocol` |
| `contract_model/trace_metadata.clj` | Canonical type vocabulary (actors, adversaries, transitions, effects, outcomes) |
| `contract_model/invariant_scenarios.clj` | S01–S41 deterministic scenarios as Clojure data + `scenario-type-registry` |
| `contract_model/invariant_runner.clj` | In-process runner; `run-all` / `print-report` / `run-and-report` |
| `protocols/protocol.clj` | `DisputeProtocol` defprotocol — the plugin interface |
| `protocols/sew.clj` | `SEWProtocol` — wraps existing contract_model dispatch |
| `protocols/dummy.clj` | `DummyProtocol` — always-pass proof-of-concept |
| `db/store.clj` | XTDB table ops + `summarise-batch` (pure) |
| `db/telemetry.clj` | Adapter: runner output → db writes |
| `io/params.clj` | EDN param loading |
| `io/results.clj` | Result serialisation |
| `sim/phase_*.clj` | One file per simulation phase; entry point `run-phase-*-sweep` |
| `sim/engine.clj` | Phase harness: `make-result`, `run-parameter-sweep`, `print-phase-header/footer` |
| `sim/batch.clj` | Aggregate N trials into summary statistics |
| `sim/fixtures.clj` | Fixture suite runner: `run-suite`, `list-suites`, `minimise-suite` |
| `sim/minimizer.clj` | Trace minimiser: reduce failing trace to 1-minimal subset |
| `sim/trajectory.clj` | Equity / spread / displacement trajectory helpers |
| `sim/multi_epoch.clj` | Multi-epoch reputation simulation (Phase J backbone) |

---

## Params and results

- Params files live in `data/params/` as EDN; must include full schema (use
  `data/params/phase-o-baseline.edn` as the canonical template)
- Results are written to `results/` (gitignored; use `git add -f` to force-track)
- Docs live in `docs/results/` (also gitignored; force-add when recording findings)

---

## Testing

- **Deterministic invariant suite** (S01–S41, 41 scenarios): `clojure -M:run -- --invariants` (in-process, no gRPC required; ~1 s)
- **Adversarial failure-mode suite** (S24–S33, 10 scenarios): `python python/invariant_suite.py` (requires gRPC server on :7070)
- **Fixture suite runner**: `clojure -M -e "(require '[resolver-sim.sim.fixtures :as f])(f/run-suite :suites/all-invariants)"`
- **gRPC server**: `nohup clojure -M:run -- -S --port 7070 > grpc-server.log 2>&1 &`
- **Monte Carlo phases** (O–AI): `scripts/monte-carlo/test-all.sh`
- **Single phase**: `clojure -M:run -- -p data/params/<phase>.edn <flags>`
- **Unit tests**: `clojure -M:test -e "(require '...)(clojure.test/run-tests '...)"`
- Integration tests (`db/telemetry_integration_test.clj`) require XTDB on localhost:5432
- Pass threshold for most hypotheses: **≥80% across all scenarios in a sweep**
