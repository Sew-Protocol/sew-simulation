# Changelog

## [Unreleased]
### Added
- **Yield Module Simulation:** Implemented full yield accrual and distribution logic in the protocol model (`lifecycle.clj`, `accounting.clj`).
  - Added `:accumulated-yield` tracking per escrow.
  - Implemented `accrue-yield` based on block-time deltas and annualized yield rates.
  - Implemented `distribute-yield` with configurable presets (`:to-sender`, `:to-recipient`, `:split-50-50`).
- **Yield Validation (Phase Y):** Created `phase_y.clj` to validate yield efficiency. Confirmed that 10% APR effectively covers protocol fees over long-duration disputes.
- **Protocol Solvency KPI:** Added a real-time solvency indicator to the Evidence Dashboard to monitor aggregate protocol health and value conservation.
- **Reorg Resilience (S46):** Added stochastic reorg and fork-reconciliation validation to ensure state derivation is idempotent across non-linear histories.

### Fixed
- **Simulation Logic (Phase AI):** Fixed capital drain bug in `phase_ai.clj` where system-wide costs were incorrectly applied in full to every individual resolver.
- **Protocol Logic:** Fixed cooldown bug that was incorrectly blocking first-time escalations.
- **Unit Tests:** Updated `replay_test.clj` to respect mandatory cooldown periods.

### Fixed
- **Simulation Gap H1:** Integrated `:identity/cheap-reentry` profile into Monte Carlo sweeps.
- **Simulation Gap H2:** Enabled and validated the per-member stochastic ring model for Phase AI sweeps.
- **CLI Robustness:** Restored `run-adversarial-search` in `src/resolver_sim/sim/adversarial.clj` to fix regression in CLI runner.
- **Reporting Logic:** Fixed bug in adversarial report generation that falsely flagged non-dominant attackers.

### Changed
- **Documentation:** Updated `docs/simulation-checklist.md` with 3-dimensional evidence classification (Execution Backing, Model Depth, Claim Confidence).
- **Dashboard UI:** Hero section now supports direct jump via URL anchor (`#dashboard`) and shows 12 curated scenarios.
