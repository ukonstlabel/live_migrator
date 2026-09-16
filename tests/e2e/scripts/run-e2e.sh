#!/usr/bin/env bash
# Orchestrator: K replicas of each strategy with a clean environment start between runs,
# then aggregation of summary.json into a comparison table (median over replicas).
#
# Parameters via environment: M, RPS, WARMUP, POST, REPS, STRATEGIES.
#   REPS=3 STRATEGIES="s0 s1 s2 s3" M=10000 RPS=800 ./run-e2e.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

REPS="${REPS:-3}"
STRATEGIES="${STRATEGIES:-s0 s1 s2 s3}"
BASE="${1:-$ROOT/tests/e2e/out/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$BASE"

# Build once; replicas reuse the artifacts.
build_agent
build_jars
export SKIP_BUILD=1

echo "=== e2e: M=$M RPS=$RPS WARMUP=${WARMUP}s POST=${POST}s REPS=$REPS -> $BASE ==="
for s in $STRATEGIES; do
    for r in $(seq 1 "$REPS"); do
        out="$BASE/${s}-rep${r}"
        echo ">>> $s replica $r -> $out"
        LABEL="${s^^}-rep${r}" "$HERE/run-${s}.sh" "$out" || echo "!!! $s rep$r failed"
        sleep 2   # clean gap between runs (GC / port / file cache)
    done
done

echo
echo "=== Aggregating ($BASE) ==="
python3 "$HERE/aggregate.py" "$BASE"
