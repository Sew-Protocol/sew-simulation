# Roadmap: Architecture Generalization

The SEW Simulator is transitioning from a protocol-specific tool to a generalized **Protocol Simulation Framework (PSF)**. 

## Objectives
1.  **Decoupling**: Separate the core simulation engine (the substrate) from protocol-specific rules (the plugins).
2.  **Standardization**: Adopt the **Common Dispute Resolution Standard (CDRS)** draft schema as the native trace and event format.
3.  **Extensibility**: Enable third-party protocols to leverage **CDRS-shaped** testing for adversarial stress-testing.

## Future Architecture
The codebase will be restructured into three distinct layers:

### 1. The Kernel Layer (`src/engine/`)
A neutral substrate for state transitions, world-state hashing, and atomic reconciliation. It remains agnostic to "disputes" or "resolvers," focusing only on state machines and economic invariants.

### 2. The Domain Layer (`src/domain/dispute/`)
Standardizes the language of decentralized dispute resolution. It defines the CDRS state buckets (`IDLE`, `CHALLENGED`, etc.) and canonical resolution outcomes.

### 3. The Implementation Layer (`src/protocols/`)
Concrete implementation modules (like `sew/` and `uma/`) that map their internal state graphs to the domain layer's standards.

## Timeline
This refactor is planned for the post-launch phase to ensure maximum stability for the initial SEW evidence release. Current engineering remains focused on SEW-specific robustness and trace verification.
