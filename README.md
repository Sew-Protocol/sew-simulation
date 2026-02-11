# sew-simulation: Dispute Resolver Incentive Monte Carlo

Clojure-based Monte Carlo simulation for validating dispute resolver network incentive economics.

**Goal**: Prove that honest resolver participation is significantly more profitable than malice (3-7×) across various escrow sizes, fee structures, and network configurations.

## Quick Start

```bash
# Clone
cd ~/Code/sew-simulation

# Run tests
clojure -M:test

# Run a single scenario
clojure -M:run params/baseline.edn

# Run parameter sweep
clojure -M:sweep params/stress/cartel.edn

# Generate report
clojure -M:clerk

# Lint
clojure -M:lint

# Format
clojure -M:fmt
```

## Project Structure

- **src/resolver_sim/model/** — Pure functions for dispute mechanics (economics, slashing, appeals)
- **src/resolver_sim/sim/** — Trial + batch + sweep orchestration
- **src/resolver_sim/io/** — Parameter loading, result serialization (EDN/CSV)
- **test/** — Unit tests + property-based invariants
- **params/** — EDN scenario definitions (baseline, whale-attack, cartel, etc.)
- **results/** — Generated outputs (gitignored)
- **notebooks/** — Clerk reports for analysis + visualization

## Architecture Principles

### Pure Model Layer
All dispute mechanics in `src/resolver_sim/model/` are **deterministic functions** given:
- Parameters (escrow size, fees, bonds, slashing)
- RNG seed
- Resolver strategy

No side effects, no global state. Easy to test, parallelize, and reason about.

### Reproducibility
Every run captures:
- Full resolved parameters
- RNG seed
- Git commit hash
- Timestamp
- JVM version
- Trial counts + parallelism config

Results can be re-run bit-for-bit.

### Property-Based Testing
Tests validate invariants:
- "Honest profit > malice profit in expectation"
- "Slashing never exceeds bond"
- "Appeal rates are consistent"
- "Results don't depend on trial ordering"

## Key Files

### Model Layer
- `src/resolver_sim/model/types.clj` — Parameter schemas
- `src/resolver_sim/model/economics.clj` — Payoff functions
- `src/resolver_sim/model/dispute.clj` — Dispute lifecycle
- `src/resolver_sim/model/appeal.clj` — Appeal dynamics
- `src/resolver_sim/model/slashing.clj` — Slashing rules
- `src/resolver_sim/model/agents.clj` — Resolver strategies

### Simulation Layer
- `src/resolver_sim/sim/trial.clj` — One trial (one dispute outcome)
- `src/resolver_sim/sim/batch.clj` — Aggregate N trials
- `src/resolver_sim/sim/sweep.clj` — Parameter sweeps

### I/O & Reporting
- `src/resolver_sim/io/params.clj` — Load/validate parameters
- `src/resolver_sim/io/results.clj` — Serialize results
- `notebooks/report.clj` — Clerk notebook for analysis

## Parameter Scenarios

See `params/` directory:

- `baseline.edn` — Standard configuration (1.5% fee, 7% bond, 2.5× slashing)
- `whale-attack.edn` — Large escrows, high malice incentive
- `cartel.edn` — Multiple resolvers colluding
- `stress/bribery.edn` — Attackers with additional bribe capital
- `stress/sybil.edn` — Many fake resolvers

Each scenario includes:
- Description
- RNG seed (for reproducibility)
- Escrow size distribution
- Strategy mix (% honest, lazy, malicious, collusive)
- Fee/bond/slashing parameters
- Trial count + parallelism

## Outputs

Each run generates:
- `run.edn` — Full params + metadata (commit, timestamp, seed)
- `summary.edn` — Aggregated statistics (means, quantiles, confidence intervals)
- `samples.csv` — Raw trial outcomes (for deeper analysis)
- `sweep.csv` — Parameter sweep results (for visualization)

Results are timestamped and gitignored.

## Example Workflow

```bash
# 1. Check baseline
clojure -M:run params/baseline.edn
# Outputs: results/2026-02-11_baseline/

# 2. Run parameter sweep
clojure -M:sweep params/stress/cartel.edn
# Outputs: results/2026-02-11_cartel_sweep/sweep.csv

# 3. Analyze in Python (optional)
# Export CSV, plot with matplotlib

# 4. Generate report
clojure -M:clerk
# Opens http://localhost:7888/report
```

## Testing

```bash
# Unit tests
clojure -M:test

# Run with coverage (optional)
clojure -M:test --reporter verbose

# Property-based tests (included in test suite)
# Look for property/ functions in test/
```

## Reproducibility

To re-run a previous result:

```bash
# Load the run.edn from results/
(read-string (slurp "results/2026-02-11_baseline/run.edn"))
; Extract seed, params, then re-run with same seed

clojure -M:run --seed <seed> --params-file <path>
```

## Development

### REPL
```bash
clojure -M:dev
```

Then in nREPL:
```clojure
(require '[resolver-sim.sim.trial :as trial])
(require '[resolver-sim.io.params :as params])

; Load params
(def baseline (params/load-edn "params/baseline.edn"))

; Run one trial
(trial/run-trial (java.util.SplittableRandom. 42) baseline)

; Run a batch
(trial/run-batch baseline 1000 42)
```

### Adding a New Model Component
1. Add `.clj` file to `src/resolver_sim/model/`
2. Implement pure functions (no I/O, no randomness except via RNG arg)
3. Add tests to `test/resolver_sim/` with property-based tests
4. Export from `model/types.clj` if part of public interface

## References

- [Clojure Guide](https://clojure.org/)
- [SplittableRandom (Java)](https://docs.oracle.com/javase/8/docs/api/java/util/SplittableRandom.html)
- [Clerk Documentation](https://clerk.vision/)
- [test.check (Property-Based Testing)](https://github.com/clojure/test.check)

## License

Apache 2.0 (same as Sew protocol)
