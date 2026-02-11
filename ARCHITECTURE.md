# Dispute Resolver Simulation - Architecture

## Overview

Monte Carlo simulation engine (Clojure/JVM) validating that honest dispute resolution incentives dominate malicious strategies across a range of economic parameters.

**Goal**: Provide credible, reproducible evidence that Sew's dispute resolution mechanism correctly aligns resolver behavior with protocol safety.

## Design Principles

1. **Pure Functions**: Model logic is side-effect-free. RNG is an explicit parameter.
2. **Deterministic Parallelism**: SplittableRandom ensures identical results regardless of execution order.
3. **Reproducibility**: Same seed + params → identical CSV output, byte-for-byte.
4. **Transparent Economics**: Fee, bond, slashing calculations are explicit and auditable.

## Core Components

### Model Layer (`src/resolver_sim/model/`)

**types.clj**: Parameter validation
- Schema for all inputs (fee-bps, bond-bps, slashing rates, etc.)
- Default values (when not in param file)
- Validation rules (fee > 0, strategy ∈ {honest, lazy, malicious, collusive})

**rng.clj**: Deterministic randomness
- Wraps `java.util.SplittableRandom` for parallel Monte Carlo
- `make-rng(seed)` → reproducible stream
- `split-rng(rng)` → two independent sub-streams (both deterministic)
- `next-double(rng)` → value in [0, 1)

**economics.clj**: Payoff calculations
- `calculate-fee(escrow-wei, fee-bps)` → fee earned per dispute
- `calculate-bond(escrow-wei, bond-bps)` → bond at stake
- `calculate-slashing-loss(bond, slash-mult)` → loss if caught
- `honest-expected-value(fee, appeal-prob)` → EV for honest strategy
- `malicious-expected-value(fee, slashing, detection-prob)` → EV for malice
- `strategy-dominance-score(ev-honest, ev-malice)` → ratio (goal: ≥3.0)

**dispute.clj**: Single dispute resolution
- `resolve-dispute(rng, escrow-wei, fee-bps, bond-bps, slash-mult, strategy, appeal-probs, detection-prob)`
- Returns: `{dispute-correct? appeal-triggered? slashed? profit-honest profit-malice strategy}`
- Strategy logic:
  - `:honest` → always judge correctly
  - `:lazy` → 50% correct (honest vs sloppy)
  - `:malicious` → 30% correct (lie often)
  - `:collusive` → 80% correct (mostly honest colluding)
- Slashing only applies if verdict is WRONG and caught (detection-prob)

### Simulation Layer (`src/resolver_sim/sim/`)

**batch.clj**: Aggregate N trials
- `run-batch(rng, n-trials, params)` → batch summary
- Computes: mean, std, min/max, quantiles (25/50/75), dominance ratio
- Handles empty sequences (defaults to 0)
- Sorts for percentile calculation

**sweep.clj**: Parameter space exploration
- `run-parameter-sweep(params, base-seed, sweep-params)` → list of batch results
- Currently: single-parameter sweeps only (1D grid)
- Example: `{:strategy [:honest :lazy :malicious :collusive]}`
- Output: one batch result per parameter value
- Note: Multi-dimensional sweeps (2D+) not yet implemented

### I/O Layer (`src/resolver_sim/io/`)

**params.clj**: Load & validate
- `load-edn(path)` → raw EDN data from file
- `merge-defaults(scenario)` → apply defaults (from types/default-params)
- `validate-and-merge(path)` → load + validate + merge + return

**results.clj**: Export results
- `write-edn(filepath, data)` → full results as EDN (auditable)
- `write-csv(filepath, results)` → spreadsheet format (plottable)
- `write-run-metadata(filepath, metadata)` → git info, timestamp, params
- `create-run-directory(base-path, scenario-id)` → timestamped directory

### CLI (`src/resolver_sim/core.clj`)

**Entry point**: `-main` (arity with args)
- Parses: `-p PARAMS -o OUTPUT [-s for sweep]`
- Loads and validates params
- Calls `run-simulation` or `run-sweep`
- Persists results + metadata
- Exit code: 0 on success, 1 on error

## Data Flow

```
Parameter File (EDN)
        ↓
   io/params.clj
   - Load
   - Validate
   - Merge defaults
        ↓
   Validated Params Map
        ↓
   core.clj
   - Single scenario: → run-simulation
   - Sweep: → run-sweep
        ↓
   Model Functions (pure)
   - Create RNG seeded
   - Call sim/batch or sim/sweep
        ↓
   sim/batch.clj or sim/sweep.clj
   - Run N trials (via model/dispute.clj)
   - Aggregate stats
        ↓
   Batch Result(s)
   - Map with :honest-mean :malice-mean :dominance-ratio etc.
        ↓
   io/results.clj
   - Write EDN
   - Write CSV
   - Write metadata
        ↓
   results/TIMESTAMP_scenario_id/
   - summary.edn
   - results.csv
   - metadata.edn
```

## Execution Model

### Single Scenario
1. Load params from `params/baseline.edn`
2. Create RNG with seed 42
3. Run 1000 trials:
   - Each trial: `resolve-dispute(rng, 10000, ...)`
   - Collect profits (honest + malice paths)
4. Aggregate: mean, std, quantiles
5. Write 3 output files
6. Display summary

### Strategy Sweep
1. Load params
2. For each strategy in [:honest :lazy :malicious :collusive]:
   - Create new RNG with same base seed
   - Run 1000 trials with that strategy
   - Aggregate
3. Collect all 4 batch results
4. Write CSV with 4 rows (one per strategy)
5. Display strategy comparison

## Key Invariants

1. **Profit is always fees-earned minus slashing-loss** (never negative expected value for honest)
2. **Slashing only occurs if verdict is wrong AND detected** (reward honest when right)
3. **RNG seeding is deterministic** (reproducibility guarantee)
4. **Honest strategy earns constant fee** (zero variance)
5. **Malice variance increases with detection probability** (risk from uncertainty)

## Testing

Unit tests in `test/resolver_sim/core_tests.clj`:

```clojure
(deftest fee-calculation-test)      ; 1000 × 150bps = 15
(deftest bond-calculation-test)     ; 10000 × 700bps = 700
(deftest slashing-loss-test)        ; 700 × 2.5 = 1750
(deftest honest-ev-test)            ; >= 0
(deftest malice-ev-test)            ; EV = fee - loss*detection
(deftest rng-determinism-test)      ; Same seed = same sequence
(deftest rng-split-determinism-test) ; Splits are deterministic
(deftest dispute-resolution-test)   ; Output shape correct
(deftest honest-vs-malice-test)     ; Honest > Malice in expectation
(deftest params-validation-test)    ; Valid/invalid scenarios
```

All 10 tests pass (18 assertions).

## Reproducibility

### Guarantees
- Same seed + params → identical mean/std/quantiles
- Date of run doesn't matter
- Hardware doesn't matter (JVM semantics)

### Metadata Captured
- RNG seed used
- Full params snapshot
- Git commit (if available)
- Timestamp
- Trial count
- JVM version (optional)

### Verifying Reproducibility
```bash
# Run 1
clojure -M:run -p params/baseline.edn > out1.txt
CSV1=$(ls -t results/*/results.csv | head -1)

# Run 2 (same seed)
clojure -M:run -p params/baseline.edn > out2.txt
CSV2=$(ls -t results/*/results.csv | head -1)

# Compare
diff <(tail +2 $CSV1) <(tail +2 $CSV2)  # Should be identical
```

## Performance

| Scenario | Trials | Time | Notes |
|----------|--------|------|-------|
| Baseline | 1,000 | 2-3s | Single scenario |
| Baseline | 10,000 | 20-30s | For narrow CI |
| Sweep (4 strats) | 1,000 each | 8-10s | Strategy comparison |
| Whale Attack | 2,000 | 4-6s | Large escrows |

No parallelism currently (sequential for reproducibility). Could pmap trials if parallelism is needed.

## Parameter File Format

```edn
{:scenario-id "baseline-v1"
 :rng-seed 42
 :escrow-size 10000  ; Single fixed size, or draw from distribution
 :resolver-fee-bps 150
 :appeal-bond-bps 700
 :slash-multiplier 2.5
 :appeal-probability-if-correct 0.05
 :appeal-probability-if-wrong 0.40
 :slashing-detection-probability 0.10
 :strategy :honest  ; Or :lazy :malicious :collusive
 :n-trials 1000
 :n-seeds 1         ; Number of independent RNG seeds to run
 :parallelism :auto ; :auto :single :cores-X
 :save-samples? false
 :save-sweep? false}
```

## Future Extensions

1. **Multi-dimensional sweeps**: 2D+ parameter grids (fee×bond×slashing)
2. **Parallel execution**: pmap with seed splitting for speed
3. **Network simulation**: N resolvers, reputation, appeal cascades
4. **Strategy learning**: Payoff-responsive strategy evolution (Mesa-style)
5. **Integration with Foundry**: Export recommended params as Solidity constants
6. **Interactive dashboard**: ClojureScript UI for parameter exploration

## Known Limitations

1. **No network effects**: Each dispute is independent (no cumulative reputation)
2. **No appeal ladder**: Appeals just have a fixed probability, not a dynamic process
3. **No resolver reputation**: All resolvers have same detection rate
4. **Escrow size**: Currently fixed per scenario (no distribution sampling yet)
5. **Single RNG seed**: Limited exploration of seed sensitivity (n-seeds param unused)

## References

- **SplittableRandom**: Lindholm, Melin (OpenJDK)
- **Reproducible Monte Carlo**: Salmon et al., "Parallel Random Numbers: As Easy as 1, 2, 3"
- **Economic Mechanisms**: Myerson, "Game Theory: Analysis of Conflict" (mechanism design basics)
