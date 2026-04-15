#!/bin/bash
# DR2: Reputation + Bond + Slashing model
# Comprehensive tests: strategy sweep + slashing + bonds + appeals

cd "$(dirname "$0")"

echo "=========================================="
echo "DR2: Bonds + Reputation (5% Bond, 1.5x Slash)"
echo "=========================================="
echo ""

echo "=== Strategy Sweep ==="
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr2-reputation.edn\" \"-s\")"

echo ""
echo "=== Slashing Sensitivity (Phase G equivalent) ==="
# Test different slashing parameters
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr2-reputation.edn\")"

echo ""
echo "=== Realistic Bond Mechanics (Phase H equivalent) ==="
# Test with freeze/appeal mechanics
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr2-reputation.edn\" \"-l\")"

echo ""
echo "=== Multi-Epoch (Phase J equivalent) ==="
# Test over multiple epochs
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr2-reputation.edn\" \"-m\")"

echo ""
echo "=== Market Exit (Phase O equivalent) ==="
clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-p\" \"params/dr2-reputation.edn\" \"-O\")"

echo ""
echo "=========================================="
echo "DR2 Tests Complete"
echo "=========================================="
