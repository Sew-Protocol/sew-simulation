#!/bin/bash
# DR1: Fee-only resolver model (no bonds, no slashing)
# Comprehensive tests: strategy sweep + appeal dynamics

cd "$(dirname "$0")"

echo "=========================================="
echo "DR1: Fee-Only (No Bonds, No Slashing)"
echo "=========================================="
echo ""

echo "=== Strategy Sweep ==="
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr1-fee-only.edn\" \"-s\")"

echo ""
echo "=== Appeal Dynamics (Phase N equivalent) ==="
# DR1: Test different appeal probabilities since that's the main lever
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr1-fee-only.edn\")"

echo ""
echo "=== Market Exit (Phase O equivalent) ==="
# DR1: Test market exit with no slashing
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr1-fee-only.edn\" \"-O\")"

echo ""
echo "=========================================="
echo "DR1 Tests Complete"
echo "=========================================="
