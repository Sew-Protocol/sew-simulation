#!/bin/bash
# Phase M: Governance Response Time Impact Analysis
# Tests how delays (0, 3, 7, 14 days) affect system security

set -e

echo "🏛️  Phase M: Governance Response Time Impact Testing"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

SCENARIOS=(
  "phase-m-instant"
  "phase-m-3day"
  "phase-m-7day"
  "phase-m-14day"
)

RESULTS_DIR="results/phase-m"
mkdir -p "$RESULTS_DIR"

echo "📊 Running 4 scenarios with different governance delays..."
echo ""

# Function to run a single test
run_test() {
  local scenario=$1
  local gov_days=$2
  
  echo "   Testing: $scenario (${gov_days}-day governance response)"
  
  if clojure -M:run -p "params/${scenario}.edn" -g -o "$RESULTS_DIR" > "$RESULTS_DIR/${scenario}.log" 2>&1; then
    echo "   ✓ $scenario PASSED"
    return 0
  else
    echo "   ✗ $scenario FAILED"
    cat "$RESULTS_DIR/${scenario}.log" | tail -20
    return 1
  fi
}

# Run all scenarios
PASSED=0
FAILED=0

for scenario in "${SCENARIOS[@]}"; do
  case $scenario in
    phase-m-instant) gov_days="0" ;;
    phase-m-3day) gov_days="3" ;;
    phase-m-7day) gov_days="7" ;;
    phase-m-14day) gov_days="14" ;;
  esac
  
  if run_test "$scenario" "$gov_days"; then
    ((PASSED++))
  else
    ((FAILED++))
  fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📋 Test Summary"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "   Results: $PASSED / 4 tests PASSED"
if [ $FAILED -gt 0 ]; then
  echo "   ⚠️  $FAILED test(s) failed"
  echo ""
  echo "   Review logs:"
  for scenario in "${SCENARIOS[@]}"; do
    echo "     - $RESULTS_DIR/${scenario}.log"
  done
  exit 1
else
  echo "   ✅ All tests passed!"
  echo ""
  echo "   Results directory: $RESULTS_DIR"
  echo "   Logs:"
  for scenario in "${SCENARIOS[@]}"; do
    echo "     - $RESULTS_DIR/${scenario}.log"
  done
  exit 0
fi
