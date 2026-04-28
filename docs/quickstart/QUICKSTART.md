# Quick Start: Dispute Resolver Simulation

## 🚀 Running Your First Simulation

### Option 1: Using the Wrapper Script (Recommended)
```bash
# Quick validation check (all phases, ~4 mins)
./test-all.sh

# Run a specific scenario suite
./run.sh -p data/params/baseline.edn
```

### Option 2: Direct Clojure Command
```bash
# Run in-process invariant validation (S01–S41)
clojure -M:run -- --invariants

# Run Monte Carlo simulation with strategy sweep
clojure -M:run -- -p data/params/baseline.edn -s
```

## Understanding Results

The simulation outputs three files to `results/TIMESTAMP_scenario_id/`:

- **summary.edn**: Full statistics (means, stds, quantiles, dominance ratio)
- **results.csv**: For plotting/analysis in Python/Excel
- **metadata.edn**: Run reproducibility info (seed, git commit, params)

### Key Metrics

**Dominance Ratio**: `honest_mean_profit / malice_mean_profit`
- \>1.0 means honest is more profitable ✅
- >3.0 means malicious strategy is strongly dominated
- Goal: **≥3.0** across all scenarios (proves incentive alignment)

**Appeal Rate & Slash Rate**: How often disputes are appealed/slashed
- Higher = safer (attacks are caught)
- Lower = more efficient (fewer disputes)

## Scenarios & Suites

Scenarios are defined in `data/params/` (Monte Carlo) and `data/fixtures/` (Deterministic).

### Main Scenarios
- **baseline.edn**: Standard parameters (1.5% fee, 7% bond, 2.5× slashing).
- **whale-attack.edn**: Large escrows + organized malice.
- **cartel.edn**: Parameter sweep for fee/bond/slashing optimization.
- **sybil-re-entry.edn**: Long-term reputation vs sybil identities (Phase J).

### Deterministic Suites
```bash
# Run all invariant scenarios
clojure -M -e "(require '[resolver-sim.sim.fixtures :as f])(f/run-suite :suites/all-invariants)"
```

## Testing

All tests pass using the utility script:
```bash
./scripts/test.sh
```

Tests cover:
- Fee & bond calculations
- RNG determinism (reproducibility)
- Honest vs malicious dominance
- State machine invariants

## Architecture Overview

```
src/resolver_sim/
  contract_model/   - State machine, invariants, and replay engine
  stochastic/       - Statistical models (rng, difficulty, decision quality)
  sim/              - Monte Carlo simulation phases and harness
  protocols/        - DisputeProtocol interface and implementations (SEW)
  io/               - Parameter loading and result serialisation
  core.clj          - CLI entry point
```

**Key principle**: All logic in `contract_model` and `stochastic` is pure (no side effects). RNG is explicitly passed as a parameter.

## Reproducibility Guarantees

**Same seed + params = exact same results, byte-for-byte.**

This works because:
1. RNG seeding is deterministic (SplittableRandom)
2. Trial ordering doesn't affect aggregates (commutative)
3. All params are snapshots (including git commit in metadata)

## Troubleshooting

**"No such file or directory"**
- Make sure you're in the project root when running scripts.
- Check that paths start with `data/params/` or `data/fixtures/`.

**Results look wrong (e.g., honest_mean = 0)**
- Check that the parameter file has required keys.
- Ensure the gRPC server is NOT required for the command you are running (most commands are in-process).

**Permission denied on run.sh**
- Make it executable: `chmod +x run.sh test-all.sh`
