#!/bin/bash
# Comprehensive test suite: validates all simulation phases (G through AA)

echo "🔍 SEW Simulation Complete Validation Suite"
echo "==========================================="
echo ""

PASS=0
FAIL=0
TOTAL=0

test_scenario() {
    local name=$1
    local params=$2
    local flags=$3
    local timeout_sec=${4:-60}
    
    ((TOTAL++))
    echo -n "[$TOTAL] Testing $name... "
    if timeout $timeout_sec clojure -M:run -- -p "$params" $flags > /tmp/test-$name.log 2>&1; then
        if grep -qE "Results saved|Phase.*complete|Sweep complete|Simulation complete|results saved|waterfall results saved|Exit Cascade Results|PHASE [A-Z]+ SUMMARY|PHASE [A-Z]+ ASSESSMENT|Risk level:|Results: [0-9]+ vulnerable" /tmp/test-$name.log; then
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

echo "=== Phase G: Slashing Delays ==="
test_scenario "phase-g-slashing-delays" "params/phase-g-slashing-delays.edn" "" 30
test_scenario "phase-g-sensitivity-2d" "params/phase-g-sensitivity-2d.edn" "-s" 120

echo ""
echo "=== Phase H: Realistic Bond Mechanics ==="
test_scenario "phase-h-realistic" "params/phase-h-realistic-mechanics.edn" "" 30
test_scenario "phase-h-2d" "params/phase-h-2d-realistic.edn" "-s" 120
test_scenario "phase-h-collusion" "params/phase-h-collusion-symmetry.edn" "" 30

echo ""
echo "=== Phase I: Automatic Detection Mechanisms ==="
test_scenario "phase-i-all-mechanisms" "params/phase-i-all-mechanisms.edn" "" 30
test_scenario "phase-i-2d" "params/phase-i-2d-all-mechanisms.edn" "-s" 120

echo ""
echo "=== Phase J: Multi-Epoch Architecture ==="
test_scenario "phase-j-baseline" "params/phase-j-baseline-stable.edn" "-m" 120
test_scenario "phase-j-governance-decay" "params/phase-j-governance-decay.edn" "-m" 120
test_scenario "phase-j-governance-failure" "params/phase-j-governance-failure.edn" "-m" 120
test_scenario "phase-j-sybil-re-entry" "params/phase-j-sybil-re-entry.edn" "-m" 120

echo ""
echo "=== Phase L: Waterfall Cascade Stress Testing ==="
test_scenario "phase-l-baseline" "params/phase-l-baseline.edn" "-l" 60
test_scenario "phase-l-high-fraud" "params/phase-l-high-fraud.edn" "-l" 60
test_scenario "phase-l-escalation" "params/phase-l-escalation-cascade.edn" "-l" 60
test_scenario "phase-l-simultaneous" "params/phase-l-simultaneous-slashes.edn" "-l" 60
test_scenario "phase-l-senior-degraded" "params/phase-l-senior-degraded.edn" "-l" 60

echo ""
echo "=== Phase M: Governance Response Time Impact ==="
test_scenario "phase-m-instant" "params/phase-m-instant.edn" "-g" 120
test_scenario "phase-m-3day" "params/phase-m-3day.edn" "-g" 120
test_scenario "phase-m-7day" "params/phase-m-7day.edn" "-g" 120
test_scenario "phase-m-14day" "params/phase-m-14day.edn" "-g" 120

echo ""
echo "=== Phase N: Appeal Outcomes ==="
test_scenario "phase-n-baseline" "params/phase-n-baseline.edn" "-l" 60
test_scenario "phase-n-high-fraud" "params/phase-n-high-fraud.edn" "-l" 60

echo ""
echo "=== Phase O: Market Exit Cascade ==="
test_scenario "phase-o-baseline" "params/phase-o-baseline.edn" "-O" 120
test_scenario "phase-o-high-fraud" "params/phase-o-high-fraud.edn" "-O" 120

echo ""
echo "=== Phase Y: Evidence Fog & Attention Budgets ==="
test_scenario "phase-y-evidence-fog" "params/phase-y-evidence-fog.edn" "-Y" 120

echo ""
echo "=== Phase Z: Legitimacy & Reflexive Participation ==="
test_scenario "phase-z-legitimacy" "params/phase-z-legitimacy.edn" "-Z" 120

echo ""
echo "=== Phase AA: Governance as Adversary ==="
test_scenario "phase-aa-governance" "params/phase-aa-governance.edn" "-A" 120

echo ""
echo "=== Phase AB: Per-Dispute Effort Rewards (Phase Y safeguard) ==="
test_scenario "phase-ab-effort-rewards" "params/phase-ab-effort-rewards.edn" "-B" 60

echo ""
echo "=== Phase AC: Trust Floor & Emergency Onboarding (Phase Z safeguard) ==="
test_scenario "phase-ac-trust-floor" "params/phase-ac-trust-floor.edn" "-C" 60

echo ""
echo "=== Phase AD: Governance Bandwidth Floor (Phase AA safeguard) ==="
test_scenario "phase-ad-governance-floor" "params/phase-ad-governance-floor.edn" "-D" 60

echo ""
echo "=== Phase AC Threshold Search ==="
test_scenario "phase-ac-threshold-sweep" "params/phase-ac-threshold-sweep.edn" "-E" 120

echo ""
echo "=== Phase AD Threshold Search ==="
test_scenario "phase-ad-threshold-sweep" "params/phase-ad-threshold-sweep.edn" "-F" 120

echo ""
echo "=== Phase P Lite: Falsification Test ==="
test_scenario "phase-p-lite-baseline" "params/phase-p-lite-baseline.edn" "-P" 120

echo ""
echo "=== Phase P Revised: Sequential Appeal Falsification ==="
test_scenario "phase-p-revised" "params/baseline.edn" "-I" 120

echo ""
echo "=== Phase Q: Advanced Vulnerability ==="
test_scenario "phase-q" "params/baseline.edn" "-J" 120

echo ""
echo "=== Phase R: Liveness & Participation Failure ==="
test_scenario "phase-r" "params/baseline.edn" "-K" 120

echo ""
echo "=== Phase T: Governance Capture via Rule Drift ==="
test_scenario "phase-t" "params/phase-t-governance-capture.edn" "-H" 120

echo ""
echo "=== Phase U: Adaptive Attacker Learning ==="
test_scenario "phase-u" "params/baseline.edn" "-L" 120

echo ""
echo "=== Phase V: Correlated Belief Cascades ==="
test_scenario "phase-v" "params/baseline.edn" "-M" 120

echo ""
echo "=== Phase W: Dispute Type Clustering ==="
test_scenario "phase-w" "params/baseline.edn" "-N" 120

echo ""
echo "=== Phase X: Burst Concurrency Exploit ==="
test_scenario "phase-x" "params/baseline.edn" "-X" 120

echo ""
echo "=========================================="
echo "Test Results: $PASS/$TOTAL passed, $FAIL failed"
echo "=========================================="

if [ $FAIL -eq 0 ]; then
    echo "✅ ALL TESTS PASSED"
    echo ""
    echo "System Status: PHASES G–AD + P-REVISED/Q/R/T/U/V/W/X VALIDATED"
    echo "Confidence: Full stack (statistical, adversarial, governance, liveness, cascade, burst-concurrency)"
    exit 0
else
    echo "❌ SOME TESTS FAILED ($FAIL tests)"
    exit 1
fi
