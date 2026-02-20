#!/bin/bash
# Adversarial parameter search: Find worst-case parameters for attackers
# This is falsification-driven testing - NOT Monte Carlo

cd "$(dirname "$0")"

echo "=========================================="
echo "Adversarial Parameter Search"
echo "(Falsification Testing)"
echo "=========================================="
echo ""

clojure -M -e "(require '[resolver-sim.core]) (resolver-sim.core/-main \"-a\")"

echo ""
echo "=========================================="
echo "Adversarial Search Complete"
echo "=========================================="
