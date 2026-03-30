#!/usr/bin/env bash
# Fires batches of requests at the latency-lab API so you can watch
# p50/p95/p99 shift in Grafana in real time.
#
# Usage: ./load.sh
# Requires: curl

API="http://localhost:8090/api"

echo "--- Resetting all samples ---"
curl -s -X POST "$API/reset" | python3 -m json.tool 2>/dev/null || curl -s -X POST "$API/reset"
echo ""

run_batch() {
  local count=$1
  local label=$2
  echo "--- Firing $count requests ($label) ---"
  curl -s -X POST "$API/simulate/batch?count=$count" | python3 -m json.tool 2>/dev/null || \
    curl -s -X POST "$API/simulate/batch?count=$count"
  echo ""
  echo "Percentiles after $label:"
  curl -s "$API/percentiles" | python3 -m json.tool 2>/dev/null || curl -s "$API/percentiles"
  echo ""
}

# Round 1 — small sample, percentiles are unstable
run_batch 20 "round 1 — 20 requests"
echo "Waiting 15s so Prometheus scrapes and Grafana updates..."
sleep 15

# Round 2 — starting to stabilize
run_batch 100 "round 2 — 100 requests"
echo "Waiting 15s..."
sleep 15

# Round 3 — clear picture of the distribution
run_batch 200 "round 3 — 200 requests"
echo "Waiting 15s..."
sleep 15

# Round 4 — high volume, percentiles very stable now
run_batch 500 "round 4 — 500 requests"
echo "Waiting 15s..."
sleep 15

echo "=== Final percentiles ==="
curl -s "$API/percentiles" | python3 -m json.tool 2>/dev/null || curl -s "$API/percentiles"
echo ""
echo "Open Grafana at http://localhost:3000 (admin / admin)"
echo "Dashboard: Latency Lab — p50 / p95 / p99"
