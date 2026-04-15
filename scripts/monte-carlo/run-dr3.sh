#!/bin/bash
# DR3: Full decentralized resolution
# Comprehensive tests: all phases (G through O)

cd "$(dirname "$0")"

echo "=========================================="
echo "DR3: Full System (10% Bond, Progressive Slashing)"
echo "=========================================="
echo ""

echo "=== Strategy Sweep ==="
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/baseline.edn\" \"-s\")"

echo ""
echo "=== Running Full Test Suite (Phases G-O) ==="
./test-all.sh
