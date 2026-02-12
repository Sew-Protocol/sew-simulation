#!/bin/bash
# Quick validation: runs key simulations to verify system integrity

echo "🔍 SEW Simulation Integrity Test"
echo "================================"
echo ""

PASS=0
FAIL=0

test_scenario() {
    local name=$1
    local params=$2
    local flags=$3
    local timeout_sec=${4:-60}
    
    echo -n "Testing $name... "
    if timeout $timeout_sec clojure -M:run -- -p "$params" $flags > /tmp/test-$name.log 2>&1; then
        if grep -q "Results saved\|Phase J complete\|Sweep complete" /tmp/test-$name.log; then
            echo "✓ PASS"
            ((PASS++))
        else
            echo "✗ FAIL (no results)"
            ((FAIL++))
        fi
    else
        echo "✗ FAIL (timeout or error)"
        ((FAIL++))
    fi
}

# Run tests (increase timeout for sweeps)
test_scenario "baseline" "params/baseline.edn" "" 30
test_scenario "phase-i" "params/phase-i-all-mechanisms.edn" "-s" 120
test_scenario "phase-h" "params/phase-h-realistic-mechanics.edn" "" 30
test_scenario "phase-j" "params/phase-j-baseline-stable.edn" "-m" 120

echo ""
echo "Results: $PASS passed, $FAIL failed"
if [ $FAIL -eq 0 ]; then
    echo "✓ All tests PASSED"
    exit 0
else
    echo "✗ Some tests FAILED"
    exit 1
fi
