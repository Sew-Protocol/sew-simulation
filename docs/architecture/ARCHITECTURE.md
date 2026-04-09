# sew-simulation Architecture

## What this system is

A Monte Carlo + contract-model simulation engine validating that honest dispute
resolution incentives dominate malicious strategies in the SEW protocol. It
operates at two levels:

1. **Statistical simulation** (`sim/`, `model/`) — probabilistic phases that
   test incentive properties across parameter spaces.
2. **Live contract simulation** (`contract_model/`) — a deterministic model of
   the actual SEW smart contract, run trial-by-trial and recorded to XTDB.

Results feed an engineering brief (`docs/results/`) that drives protocol
implementation and remediation.

---

## Design principles

- **Functional core, imperative shell**: all model and simulation logic is pure
  (no I/O, no DB). Only `io/` (file I/O) and `db/` (XTDB) are effectful.
- **Pure functions, explicit RNG**: randomness is an explicit parameter — same
  seed + params → identical output, byte-for-byte.
- **Reproducibility**: every run is auditable via params EDN + git commit.
- **Transparent economics**: fee, bond, slashing calculations are explicit and
  independently verifiable.

---

## Namespace map

```
src/resolver_sim/

  contract_model/       ← deterministic SEW contract model (pure)
    accounting.clj        fee/bond/slashing arithmetic
    authority.clj         resolver authority checks
    invariants.clj        CM invariant assertions
    lifecycle.clj         escrow lifecycle transitions
    resolution.clj        dispute resolution logic
    runner.clj            single trial entry point → result map
    state_machine.clj     escrow FSM
    types.clj             domain types and param schema

  db/                   ← imperative shell: XTDB persistence
    store.clj             sew_trial_outcomes + sew_escrow_events tables,
                          queries, summarise-batch (pure aggregate helper)
    telemetry.clj         adapter: runner output → db writes

  sim/                  ← statistical simulation phases (pure)
    phase_o.clj … phase_aa.clj   individual hypothesis phases
    batch.clj, sweep.clj          batch runners
    adversarial.clj, waterfall.clj, multi_epoch.clj, reputation.clj
    governance_delay.clj, governance_impact.clj
    appeal_outcomes.clj, batch_integration.clj

  model/                ← statistical / economic models (pure)
    types.clj, rng.clj, economics.clj, dispute.clj
    bribery_markets.clj, contingent_bribery.clj
    correlated_failures.clj, decision_quality.clj
    delegation.clj, difficulty.clj
    escalation_economics.clj, evidence_costs.clj, evidence_spoofing.clj
    information_cascade.clj, liveness_failures.clj
    panel_decision.clj, resolver_ring.clj

  governance/           ← governance rule models (pure)
    rules.clj

  adversaries/          ← adversary strategy models (pure)
    strategy.clj

  oracle/               ← detection models (pure)
    detection.clj

  io/                   ← imperative shell: file I/O
    params.clj            load + validate EDN params
    results.clj           write CSV / EDN / metadata

  core.clj              ← CLI entry point (imperative shell)
```

---

## Functional core / imperative shell boundary

```
FUNCTIONAL CORE (no I/O, easily testable)
  contract_model/*, model/*, sim/*, governance/*, adversaries/*, oracle/*

IMPERATIVE SHELL (effectful)
  db/*      — XTDB reads/writes via evaluation.xtdb (from eval-engine dep)
  io/*      — file reads/writes
  core.clj  — CLI, wires shell to core
```

**Rule**: namespaces in the functional core must never import `evaluation.xtdb`
or any `db/*` namespace. Shell code flows inward; core code never reaches out.

---

## Cross-project coupling (eval-engine)

`sew-simulation` depends on `eval-engine` as a local dep:
```clojure
og/eval-engine {:local/root "../og/eval-engine"}
```

Only `resolver-sim.db.*` may import `evaluation.xtdb` (eval-engine's shared
XTDB infrastructure). The rest of the codebase is decoupled from eval-engine.

When `evaluation.xtdb` moves to `evaluation.db.xtdb` (planned eval-engine
restructure), update `db/store.clj` require accordingly.

---

## XTDB persistence layer (`db/`)

Two tables, auto-created by XTDB on first INSERT:

| Table | Purpose |
|---|---|
| `sew_trial_outcomes` | One row per simulation trial — strategy, final state, profits, invariant results |
| `sew_escrow_events` | One row per escrow state transition within a trial — valid-time semantics |

Valid-time semantics: `_valid_from` = simulated block timestamp. Queries with
`FOR VALID_TIME AS OF` reproduce the escrow state at any point in the simulated
chain timeline.

SQL literals (not JDBC `?` params) are used for INSERTs because XTDB 2.x
pgwire rejects double-parenthesised VALUES from `preferQueryMode=simple` param
substitution. See `evaluation.xtdb/sql-str` etc.

Pass `nil` as datasource to skip all writes — enables offline simulation runs
and unit tests without a live XTDB instance.

---

## Evaluation pipeline (statistical simulation)

```
params/*.edn
  ↓  io/params.clj  (load + validate + merge defaults)
Validated params map
  ↓  core.clj  (CLI dispatch)
  ├── sim/phase_*.clj  (statistical phase runner)
  │     ↓  model/*.clj  (pure economic/adversarial models)
  │   Phase summary printed + saved to results/
  │
  └── contract_model/runner.clj  (live contract trial)
        ↓  db/telemetry.clj  (write to XTDB if ds provided)
      Trial result map → XTDB
```

---

## Namespace growth guidance

### `sim/` — currently ~15 files
Stays manageable to ~25 before navigation suffers. When it tips, group by
what is being tested rather than chronologically:

```
sim/
  statistical/    ← early model-validation phases (A–N)
  live/           ← contract-model phases (O–AA) + future
  adversarial.clj, batch.clj, sweep.clj, waterfall.clj
```

Do not split prematurely — wait until a flat listing is confusing to navigate.

### `model/` — currently ~10 files
Split only when two distinct sub-domains emerge (e.g. `model/economic/` vs
`model/adversarial/`). `bribery_markets.clj` and `contingent_bribery.clj` are
natural neighbours; keep them together.

### `db/` — currently 2 files
New tables get new files here (e.g. `db/governance.clj` for governance event
tables). Do not grow `store.clj` indefinitely — one file per table group.

### `contract_model/` — currently 8 files
If the live contract simulation expands significantly (e.g. multi-escrow
workflows, oracle integration), sub-group into `contract_model/state/` and
`contract_model/oracle/`. Not needed yet.

---

## Validation phases

27 phases implemented (G through AA). Results in `docs/results/`.

| Range | Focus |
|---|---|
| G–N | Statistical model baseline and edge cases |
| O–X | Contract model: lifecycle, adversarial, divergence |
| Y | Evidence fog and attention budget exhaustion |
| Z | Legitimacy and reflexive participation loop |
| AA | Governance as adversary (capacity attack + rule drift) |

Key findings documented in `docs/results/PHASE_YZA_FINDINGS.md`.
Remediation status tracked in `docs/results/COMPLETE_VALIDATION_SUMMARY.md`.

---

## Testing

- **Unit tests**: `test/resolver_sim/contract_model/*_test.clj` — pure functions,
  no XTDB required (ds=nil)
- **Integration tests**: `test/resolver_sim/db/telemetry_integration_test.clj` —
  requires live XTDB on localhost:5432
- **Simulation smoke tests**: `test-all.sh` — runs all 27 phases, checks for
  expected completion output (27/27 passing)

Run unit tests:
```bash
clojure -M:test -e "
  (require 'clojure.test)
  (require 'resolver-sim.db.telemetry-test)
  (clojure.test/run-tests 'resolver-sim.db.telemetry-test)"
```
