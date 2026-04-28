# Python Simulation Tools

This directory contains the Python-side validation and tooling that interacts with the SEW simulation engine via gRPC.

## Package: `sew_sim/`
Core Python package for interfacing with the gRPC simulation server. 

- `client.py`: Auto-generated or hand-rolled gRPC client stubs.
- `types.py`: Mirror of Clojure's canonical types for data exchange.

## Root Scripts
- `invariant_suite.py`: Primary adversarial suite. Launches/connects to a gRPC simulation server to replay failure-mode traces and check invariant satisfaction.
- `property_tests.py`: Hypothesis-based property testing over the protocol interface.
- `eth_failure_modes.py` / `eth_failure_modes_2.py`: Historical failure analysis scripts comparing simulation outcomes to Solidity-based findings.
- `phase_*.py`: Analytical scripts for Monte Carlo phase data.

## Running Tests
Ensure the gRPC server is running:
```bash
clojure -M:run -- -S --port 7070 &
```

Then run the invariant suite:
```bash
python3 invariant_suite.py
```
