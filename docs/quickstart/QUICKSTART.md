# Quick Start: Dispute Resolver Simulation

## Running Your First Simulation

### Option 1: Using the Wrapper Script (Recommended)
```bash
cd ~/Code/sew-simulation

# Single scenario (baseline parameters)
./run.sh -p params/baseline.edn

# Strategy sweep (test honest vs malicious vs lazy)
./run.sh -p params/baseline.edn -s
```

### Option 2: Direct Clojure Command
```bash
# Single scenario
clojure -M:run -- -p params/baseline.edn

# Strategy sweep
clojure -M:run -- -p params/baseline.edn -s

# Help
clojure -M:run -- --help
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

## Scenarios

### Baseline (baseline.edn)
Standard parameters: 1.5% fee, 7% bond, 2.5× slashing
- 1000 trials
- Tests standard resolver behavior

### Whale Attack (whale-attack.edn)
Large escrows + organized malice
- 2000 trials (more variance)
- Single scenario run only
- Tests economics under extreme amounts

### Cartel (cartel.edn)
Multi-dimensional parameter sweep
- 500 trials per scenario
- Parameter ranges: fee, bond, slashing
- Finds optimal parameter combinations
- **WIP**: Sweep runner needs grid implementation

### Stress Tests
- **bribery.edn**: Attackers have extra capital
- **sybil.edn**: Many fake resolvers in network

## Interpreting CSV Output

```
strategy,n_trials,honest_mean,honest_std,...,dominance_ratio
:honest,1000,150.00,0.00,...,1.00
:lazy,1000,150.00,145.98,...,1.09
:malicious,1000,150.00,437.76,...,4.58
```

For each strategy:
- **honest_mean**: Average fee earned
- **honest_std**: Profit variance (0 for honest = deterministic)
- **malice_mean**: Average payout after slashing
- **dominance_ratio**: How many times better honest is

## Testing

All tests pass using the utility script:
```bash
./scripts/test.sh
```

Tests cover:
- Fee & bond calculations
- RNG determinism (critical for reproducibility)
- Honest vs malicious dominance
- Parameter validation

## Next Steps

1. **Export CSV & plot in Python**
   ```bash
   python3 -c "import pandas as pd; df = pd.read_csv('results/*/results.csv'); print(df)"
   ```

2. **Generate Clerk report**
   ```bash
   clojure -M:clerk
   # Open http://localhost:7888
   ```

3. **Run full cartel sweep** (4×4×4 = 64 scenarios, WIP)
   ```bash
   ./run.sh -p params/cartel.edn
   ```

4. **Validate with Foundry**
   Use recommended parameters from simulation in contract invariant tests

## Architecture Overview

```
src/resolver_sim/
  model/
    types.clj       - Parameter schemas & validation
    rng.clj         - Deterministic RNG (SplittableRandom)
    economics.clj   - Profit calculations
    dispute.clj     - Single dispute resolution
  sim/
    batch.clj       - N trials → aggregated stats
    sweep.clj       - Parameter sweep runner
  io/
    params.clj      - Load & validate EDN params
    results.clj     - Export to CSV/EDN/JSON
  core.clj          - CLI entry point
```

**Key principle**: Model functions are pure (no side effects). RNG is explicit parameter.

## Reproducibility Guarantees

Same seed + params = **exact same results**, byte-for-byte.

This works because:
1. RNG seeding is deterministic (SplittableRandom)
2. Trial ordering doesn't affect aggregates (commutative)
3. All params are snapshots (including git commit in metadata)

Run the same scenario 100 times, get identical CSV output.

## Performance

- **1000 trials**: ~2-3 seconds
- **10,000 trials**: ~20-30 seconds
- Parallelism: Sequential for reproducibility (can pmap if needed)

## Troubleshooting

**"No such file or directory"**
- Make sure you're in ~/Code/sew-simulation when running ./run.sh
- Or use absolute path: ~/Code/sew-simulation/run.sh -p params/baseline.edn

**Results look wrong (e.g., honest_mean = 0)**
- Check parameter file has required keys
- Validate with: ./run.sh -p params/baseline.edn (no -s flag for single scenario)

**CSV shows NaN or Infinity**
- Dominance ratio can be Infinity if malice_mean = 0
- This is correct (honest infinitely better)

**Permission denied on run.sh**
- Make it executable: chmod +x ~/Code/sew-simulation/run.sh
