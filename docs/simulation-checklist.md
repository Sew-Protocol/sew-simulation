# Simulation Evidence Checklist

This checklist tracks the maturation of scenarios from pinned-derivation to high-confidence simulator-backed status.

## 3-Dimensional Evidence Classification

| Dimension | Scope | Target Examples |
| :--- | :--- | :--- |
| **Execution backing** | How was the trace generated? | `pinned-derivation`, `simulator-backed-single-path`, `simulator-backed-parameter-sweep` |
| **Model depth** | Adversarial coverage | `single-path`, `branch-covered`, `counterfactual`, `adversarial-sweep` |
| **Claim confidence** | Strength of evidence | `provisional`, `medium`, `high` |

## Promotion Criteria

1. **Evidence Maturity:** A scenario moves from `pinned-derivation` to `simulator-backed` only when the simulator executes it and CI verifies the trace hash.
2. **Confidence Maturity:** `high` confidence requires sufficient branch coverage, adversarial variants (including negative "should-fail" cases), and sensitivity analysis.
3. **Negative Variants:** For every passing scenario, implement at least one "should-fail/reject" variant.

## Required Meta-Scenarios
- `reference-suite-integrity-v1`: Verifies meta-data consistency (trace hashes, artifact sources, confidence levels).
- `contract-sim-parity-v1`: Validates simulator/Solidity parity on core state transitions.
- `economic-assumption-sensitivity-v1`: Stress-tests complex assumptions across pessimistic parameter ranges.
