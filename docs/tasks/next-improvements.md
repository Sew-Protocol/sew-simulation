  1. High-Priority Infrastructure & Documentation
   * Finalise README.md & data/fixtures/README.md: These are now structurally complete, but the content in docs/ and the repo-level documentation should reflect the new
     abstraction layer (DisputeProtocol kernel vs. SEW plugin).
   * Suite Manifest: Create the data/fixtures/suites/manifest.edn as a single registry file, making it easy for users to list and discover all available suites.
   * Determinism Proof: Execute the "Double Run" check (run-suite twice, byte-compare golden/ reports) and document the result as a guarantee.

  2. Trace Minimisation (Refining Implementation)
   * Invariant-Aware Minimisation: Debug the replay engine timing to ensure the :time-lock-integrity invariant fires reliably during minimization (currently minimized as a
     "happy path" trace).
   * Greedy Multi-Step Pruning: Ensure the minimize function correctly prunes agent actors that are no longer referenced in the minimized event sequence.

  3. Abstraction (The "Protocol Kernel" Layer)
   * Extract DisputeProtocol Interface: Refactor src/resolver_sim/contract_model/ to isolate protocol-agnostic primitives (actions, resolution states) from SEW-specific
     logic.
   * Module Migration: Move all SEW-specific implementation (transitions, economics, invariants) to src/protocols/sew/.
   * Cross-Protocol Example: Implement a minimal UMA-style or Dummy protocol within the framework to prove that the kernel successfully abstracts away the implementation
     details.

  4. Canonical Vocabulary Expansion
   * Event Typing: Implement the :transition/type and :effect/type classifications we discussed to enable structured trace filtering (e.g., "Show all :economic effects").
   * Outcome & Integrity Mapping: Fully integrate the Resolution Trace Metadata (:outcome, :integrity, :failure) into the run-suite reporter.

  5. Roadmap Coverage & Testing
   * Coverage Manifest: Populate the "Coverage Manifest" in data/fixtures/README.md with:
       - Covered: Escrow lifecycle, Baseline timeout, Escalation spam.
       - In Progress: Resolver withdrawal guard, Fee cap breaches.
       - Backlog: Collusion rings, Token pathologies (FoT), Cross-vault leakage.
   * Golden Snapshot Discipline: Formally define the "Reviewer-only" update rule for data/fixtures/golden/ files to prevent drift from being accidentally blessed.
