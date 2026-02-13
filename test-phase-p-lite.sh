#!/bin/bash

# Phase P Lite: Falsification Test
# Tests whether 99% mechanism confidence survives realistic conditions

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "═══════════════════════════════════════════════════════════"
echo "Phase P Lite: Minimal Falsification Test"
echo "═══════════════════════════════════════════════════════════"
echo ""

# Test 1: Baseline (light load, no correlation)
echo "Test 1/3: Baseline (light load, rho=0.0)"
echo "Running: phase-p-lite-baseline"
if clojure -M:run -- -p params/phase-p-lite-baseline.edn 2>&1 | tee results/phase-p-lite-baseline.log | grep -q "Scenario"; then
  echo "✓ Baseline complete"
else
  echo "✗ Baseline failed"
  exit 1
fi

echo ""

# Test 2: Heavy load with moderate correlation
echo "Test 2/3: Heavy load + moderate correlation (rho=0.5)"
echo "Running: phase-p-lite-heavy"
if clojure -M:run -- -p params/phase-p-lite-heavy.edn 2>&1 | tee results/phase-p-lite-heavy.log | grep -q "Scenario"; then
  echo "✓ Heavy load complete"
else
  echo "✗ Heavy load failed"
  exit 1
fi

echo ""

# Test 3: Extreme load with strong correlation
echo "Test 3/3: Extreme load + strong correlation (rho=0.8)"
echo "Running: phase-p-lite-extreme"
if clojure -M:run -- -p params/phase-p-lite-extreme.edn 2>&1 | tee results/phase-p-lite-extreme.log | grep -q "Scenario"; then
  echo "✓ Extreme complete"
else
  echo "✗ Extreme failed"
  exit 1
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "Phase P Lite Complete"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Results saved to results/phase-p-lite-*.log"
echo ""
echo "Scenario Determination:"
echo "  If dominance > 1.5x at heavy+rho=0.8: Scenario A (ROBUST)"
echo "  If dominance inverts at rho > 0.5: Scenario B (BRITTLE)"
echo "  If dominance < 0.5 at moderate: Scenario C (BROKEN)"
