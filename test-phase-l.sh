#!/bin/bash
# Phase L Waterfall Stress Testing - Full Suite
# Tests coverage adequacy under various stress conditions

set -e

REPO_DIR="/home/user/Code/sew-simulation"
RESULTS_DIR="$REPO_DIR/results/phase-l"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║           Phase L: Coverage Waterfall Stress Testing          ║"
echo "║              (Minimum senior coverage ratio)                  ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Create results directory
mkdir -p "$RESULTS_DIR"

# Colors
BLUE='\033[0;34m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

run_test() {
    local scenario=$1
    local desc=$2
    local timeout=$3
    
    echo -e "${BLUE}Testing: $scenario${NC}"
    echo "  $desc"
    
    cd "$REPO_DIR"
    
    if timeout "$timeout" clojure -M:run -- -p "params/$scenario.edn" -l 2>&1 | tee "$RESULTS_DIR/${scenario}.log"; then
        echo -e "${GREEN}✓ PASS - $scenario${NC}"
        return 0
    else
        echo -e "${RED}✗ FAIL - $scenario${NC}"
        return 1
    fi
}

echo "═════════════════════════════════════════════════════════════════"
echo "Scenario 1: Baseline (normal fraud rate, expected behavior)"
echo "─────────────────────────────────────────────────────────────────"
run_test "phase-l-baseline" "5 seniors, 50 juniors, 10% fraud" 60 || true
echo ""

echo "═════════════════════════════════════════════════════════════════"
echo "Scenario 2: High Fraud (30% fraud - stress coverage multiplier)"
echo "─────────────────────────────────────────────────────────────────"
run_test "phase-l-high-fraud" "Same pool, 30% fraud rate (3× higher)" 60 || true
echo ""

echo "═════════════════════════════════════════════════════════════════"
echo "Scenario 3: Simultaneous Slashes (batch processing contention)"
echo "─────────────────────────────────────────────────────────────────"
run_test "phase-l-simultaneous-slashes" "10+ slashes in same epoch" 60 || true
echo ""

echo "═════════════════════════════════════════════════════════════════"
echo "Scenario 4: Senior Pool Degradation (limited slack capacity)"
echo "─────────────────────────────────────────────────────────────────"
run_test "phase-l-senior-degraded" "Smaller senior pool (75k vs 100k)" 60 || true
echo ""

echo "═════════════════════════════════════════════════════════════════"
echo "Scenario 5: Escalation Cascade (multiple seniors saturating)"
echo "─────────────────────────────────────────────────────────────────"
run_test "phase-l-escalation-cascade" "20% fraud escalates to senior" 60 || true
echo ""

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Test Execution Summary                      ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Count successes - check if logs exist and contain results (no errors)
PASS_COUNT=0
for log in "$RESULTS_DIR"/*.log; do
    # Check if log file successfully completed (has results saved message)
    if grep -q "results saved to:" "$log" 2>/dev/null; then
        PASS_COUNT=$((PASS_COUNT + 1))
    fi
done
TOTAL_COUNT=5

echo "Results: $PASS_COUNT / $TOTAL_COUNT tests passed"
echo "Details: $RESULTS_DIR/"
echo ""

if [ "$PASS_COUNT" -eq "$TOTAL_COUNT" ]; then
    echo -e "${GREEN}✓ All Phase L tests PASSED${NC}"
    echo ""
    echo "Next step: Review Phase L findings in PHASE_L_FINDINGS.md"
    exit 0
else
    echo -e "${RED}✗ Some Phase L tests FAILED${NC}"
    echo "Review logs in $RESULTS_DIR/ for details"
    exit 1
fi
