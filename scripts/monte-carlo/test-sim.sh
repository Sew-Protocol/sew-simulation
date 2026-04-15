#!/usr/bin/env bash
# test-sim.sh — run the full live simulation test suite (invariants + property tests)
#
# Usage:
#   ./test-sim.sh            # start server, run both suites, tear down
#   ./test-sim.sh --no-server  # skip server startup (already running on :7070)
#
# Exit code: 0 only if both suites pass.

set -euo pipefail

START_SERVER=true
for arg in "$@"; do
  [[ "$arg" == "--no-server" ]] && START_SERVER=false
done

SERVER_PID=""

# ---------------------------------------------------------------------------
# Server lifecycle
# ---------------------------------------------------------------------------

start_server() {
  echo "▶  Starting gRPC server..."
  nohup clojure -M:run -- -S > /tmp/sew-grpc.log 2>&1 &
  SERVER_PID=$!
  disown "$SERVER_PID"

  # Wait up to 30 s for the port to become available
  for i in $(seq 1 30); do
    if ss -tlnp 2>/dev/null | grep -q ':7070'; then
      echo "   Server ready (${i}s)"
      return 0
    fi
    sleep 1
  done
  echo "✗  Server failed to start within 30 s — check /tmp/sew-grpc.log"
  exit 1
}

stop_server() {
  if [[ -n "$SERVER_PID" ]]; then
    echo "▶  Stopping gRPC server (PID $SERVER_PID)..."
    kill "$SERVER_PID" 2>/dev/null || true
  fi
}

trap stop_server EXIT

# ---------------------------------------------------------------------------
# Run suites
# ---------------------------------------------------------------------------

if $START_SERVER; then
  start_server
else
  echo "   Skipping server startup (--no-server)"
fi

cd "$(dirname "$0")"

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Suite 1: Invariant scenarios (deterministic)"
echo "═══════════════════════════════════════════════════════════════════════"
python python/invariant_suite.py
SUITE1=$?

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  Suite 2: Property tests (Hypothesis)"
echo "═══════════════════════════════════════════════════════════════════════"
python -m pytest python/property_tests.py -v
SUITE2=$?

echo ""
if [[ $SUITE1 -eq 0 && $SUITE2 -eq 0 ]]; then
  echo "✓  All simulation tests passed"
  exit 0
else
  echo "✗  One or more suites failed (invariant=$SUITE1, property=$SUITE2)"
  exit 1
fi
