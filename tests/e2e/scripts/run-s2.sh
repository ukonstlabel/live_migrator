#!/usr/bin/env bash
# S2 — Rolling restart under load. Two backends behind the load balancer. At t0, each one
# in turn: drain (remove from active) -> kill -> start a COLD JVM -> wait for health ->
# return to active. The client sees no connection failures (there is always a live backend),
# but during each step capacity is halved and the returned instance is cold (JIT warmup).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

OUTDIR="${1:-$ROOT/tests/e2e/out/s2}"
LABEL="${LABEL:-S2}"
mkdir -p "$OUTDIR"
B1=8081; B2=8082

build_jars

ALL_PIDS=()
cleanup() { kill "${ALL_PIDS[@]}" 2>/dev/null || true; }
trap cleanup EXIT

echo "[s2] starting LB + 2 backends (M=$M) ..."
LB_PID=$(start_lb "localhost:$B1,localhost:$B2" "$OUTDIR/lb.log"); ALL_PIDS+=("$LB_PID")
P1=$(start_backend "$B1" "$OUTDIR/b1.log"); ALL_PIDS+=("$P1")
P2=$(start_backend "$B2" "$OUTDIR/b2.log"); ALL_PIDS+=("$P2")
wait_port_health "$B1"; wait_port_health "$B2"; wait_health   # front reachable

T0=$(( $(now_ms) + WARMUP * 1000 ))
echo "[s2] load: λ=$RPS, warmup=${WARMUP}s, post=${POST}s, t0=$T0"
"$JAVA" -jar "$LOADGEN_JAR" url="$URL" rps="$RPS" t0EpochMs="$T0" postSec="$POST" \
    out="$OUTDIR" label="$LABEL" $LOADGEN_EXTRA > "$OUTDIR/loadgen.log" 2>&1 &
LOAD_PID=$!; ALL_PIDS+=("$LOAD_PID")

sleep_until_ms "$T0"
echo "[s2] t0 - rolling restart, one backend at a time"

# Backend 1: drain -> kill -> cold start -> health -> return
lb_set "localhost:$B2"
kill -TERM "$P1" 2>/dev/null || true; wait "$P1" 2>/dev/null || true
P1=$(start_backend "$B1" "$OUTDIR/b1-cold.log"); ALL_PIDS+=("$P1")
wait_port_health "$B1"
lb_set "localhost:$B1,localhost:$B2"

# Backend 2: same
lb_set "localhost:$B1"
kill -TERM "$P2" 2>/dev/null || true; wait "$P2" 2>/dev/null || true
P2=$(start_backend "$B2" "$OUTDIR/b2-cold.log"); ALL_PIDS+=("$P2")
wait_port_health "$B2"
lb_set "localhost:$B1,localhost:$B2"
echo "[s2] rolling restart complete; loadgen capturing recovery tail"

wait "$LOAD_PID"
echo "[s2] ---- summary ----"; cat "$OUTDIR/summary.json"
