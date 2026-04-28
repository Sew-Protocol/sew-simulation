# SEW Simulation — System Overview

**Status**: Active development; adversarial suite complete, Foundry differential testing in progress
**Languages**: Python (adversarial layer), Clojure/JVM (contract model + Monte Carlo)
**Adversarial scenarios**: 33 passing (S01–S23 + F1–F10 Ethereum failure modes)
**Monte Carlo phases**: 30+ (G–AD)

## What This Is

A four-layer security simulation stack for the SEW escrow and dispute-resolution protocol.

Each layer tests a different class of failure:

| Layer | What it tests | Technology |
|-------|--------------|------------|
| 1 — Adversarial invariant suite | Multi-actor, time-ordered attack scenarios | Python + Clojure gRPC |
| 2 — Contract model | State machine correctness, invariants, DR1/DR2/DR3 escalation | Clojure |
| 3 — Foundry differential testing | EVM vs model divergence | Python + Forge/Anvil (in progress) |
| 4 — Monte Carlo simulation | Resolver incentive economics, parameter sweeps | Clojure |

## Key Results

| Strategy | Avg Profit | vs Honest | Notes |
|----------|------------|-----------|-------|
| Honest | 150.00 | — | Deterministic, always correct |
| Lazy | 137.75 | -8.2% | 50% correct, occasional slashing |
| Malicious | 32.75 | -78.2% | **4.58× worse** than honest |
| Collusive | 134.25 | -10.5% | Coordination doesn't help |

**Conclusion**: Honest participation earns 3-5× more profit than malicious strategies.

## How to Use

### One-Time Setup
```bash
cd ~/Code/sew-simulation
clojure -M:test  # Verify environment (optional)
```

### Run Simulations
```bash
# Single scenario (baseline parameters)
clojure -M:run -p data/params/baseline.edn

# Compare all 4 strategies
clojure -M:run -p data/params/baseline.edn -s

# Large escrow scenario (whale attack)
clojure -M:run -p data/params/whale-attack.edn

# Results appear in: results/TIMESTAMP_scenario_id/
# - summary.edn: Full statistics
# - results.csv: Spreadsheet format
# - metadata.edn: Reproducibility info
```

### Analyze Results
```bash
# View as table
cat results/*/results.csv | column -t -s,

# Plot with Python/Excel
python3 -c "import pandas as pd; df = pd.read_csv('results/*/results.csv'); print(df)"
```

## Scenarios Available

| Scenario | Purpose | Params | Trials |
|----------|---------|--------|--------|
| baseline.edn | Standard economics | 1.5% fee, 7% bond, 2.5× slash | 1,000 |
| whale-attack.edn | Large escrows | $10k-$100k amounts | 2,000 |
| cartel.edn | Parameter sensitivity | 4×4×4 grid sweep (WIP) | 500 |
| stress/bribery.edn | Attackers with capital | Extra resources | Ready |
| stress/sybil.edn | Many fake resolvers | Network size = 100+ | Ready |

## What It Validates

✅ **Honest strategies dominate**: Honest > Lazy > Collusive > Malicious
✅ **Malicious is unprofitable**: -78% profit vs honest
✅ **Network scales safely**: Economics hold at whale-scale ($100k escrows)
✅ **Results are reproducible**: Same seed = same output (bit-for-bit)
✅ **Economics are auditable**: Every calculation is transparent

## Architecture Summary

```
Model Layer (pure functions)
├── dispute.clj → one dispute resolution
├── economics.clj → fee/bond/slashing calcs
├── rng.clj → deterministic randomness
└── types.clj → parameter validation

Simulation Layer (orchestration)
├── batch.clj → N trials → stats
└── sweep.clj → parameter sweeps

I/O Layer (persistence)
├── params.clj → load EDN configs
└── results.clj → export CSV/EDN

CLI (core.clj)
├── Single scenario mode
└── Strategy sweep mode (-s flag)
```

**Key principle**: Model functions are pure (no side effects). RNG is an explicit parameter. This ensures reproducibility.

## Reproducibility Guarantees

Same seed + params = **identical CSV output** (deterministic)

```bash
# Verify reproducibility
clojure -M:run -p data/params/baseline.edn > run1.log
CSV1=$(ls -t results/*/results.csv | head -1)

clojure -M:run -p data/params/baseline.edn > run2.log
CSV2=$(ls -t results/*/results.csv | head -1)

# Should be identical (except timestamps)
diff <(tail +2 $CSV1) <(tail +2 $CSV2)  # Exit 0 = identical
```

## Testing

All tests pass:
```bash
clojure -M:test -e "(do (require '[clojure.test :as t]) (require '[resolver-sim.core-tests]) (t/run-tests 'resolver-sim.core-tests))"

# Output: Ran 10 tests containing 18 assertions. 0 failures, 0 errors.
```

Test coverage:
- Fee calculations (escrow × fee-bps)
- Bond calculations (escrow × bond-bps)
- Slashing mechanics (bond × multiplier)
- RNG determinism (critical for reproducibility)
- Strategy dominance (honest > malice)
- Parameter validation

## Performance

| Scenario | Trials | Time |
|----------|--------|------|
| Baseline | 1,000 | 2-3s |
| Baseline | 10,000 | 20-30s |
| Sweep (4 strats) | 1,000 each | 8-10s |

Fast enough for interactive parameter exploration.

## Documentation

- **QUICKSTART.md**: Get started guide (how to run, interpret results)
- **ARCHITECTURE.md**: Technical deep-dive (components, data flow, invariants)
- **README.md** (in sew-simulation/): Project overview

## For Governance & Auditors

**Key facts to communicate**:
1. Code is open and version-controlled (git)
2. Results are reproducible (same seed = same output)
3. Economics are transparent (every calculation visible)
4. Claim is strong: Honest 4.58× better than malicious
5. Scales at whale-level ($100k escrows)

**To verify**: 
- Clone repo, run `clojure -M:run -p data/params/baseline.edn -s`
- Compare CSV output to published baseline
- Run tests: `clojure -M:test -e "(do ...)"`

## Integration with Mainnet

Once parameter sweeps identify optimal fee/bond/slash rates:

1. Export recommended params as Solidity constants
2. Update contract deployment scripts
3. Run Foundry invariant tests with recommended values
4. Publish governance proposal with evidence

## Next Steps (Your Team)

**This week**:
- [ ] Run cartel sweep to find optimal parameters
- [ ] Plot results in Python/Matplotlib
- [ ] Draft governance recommendation

**Next week**:
- [ ] Implement multi-dimensional sweeps (2D+ grids)
- [ ] Add network simulation (reputation, appeal cascades)
- [ ] Generate Clerk interactive report

**Week 3**:
- [ ] Integrate with Foundry (fuzz tests with recommended params)
- [ ] Publish governance proposal
- [ ] Release for mainnet deployment

## Contact / Questions

Simulation is self-contained and reproducible. All logic is in code, all parameters are in EDN files, all results are in CSV. Nothing is hidden.

---

**TL;DR**: You have credible evidence that honest resolver incentives dominate malicious strategies (4.58×). Results are reproducible, auditable, and ready for governance/audit.
