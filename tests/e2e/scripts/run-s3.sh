#!/usr/bin/env bash
# S3 — Blue-green under load. A live "blue" set (2 backends) behind the load balancer. At t0
# a FRESH "green" set (2 backends) is brought up, then active is atomically switched
# blue->green, after which blue is killed. Client downtime ≈ 0 (atomic switch), but green is
# cold at the moment of the switch -> the JIT-warmup tail is visible; all at the cost of ×2
# infrastructure (4 instances at once). GREEN_WARMUP>0 — a warmup pause for green before the switch.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

OUTDIR="${1:-$ROOT/tests/e2e/out/s3}"
LABEL="${LABEL:-S3}"
GREEN_WARMUP="${GREEN_WARMUP:-0}"   # seconds of green warmup before the switch (0 = cold switch)
mkdir -p "$OUTDIR"
BLUE1=8081; BLUE2=8082; GREEN1=8083; GREEN2=8084

build_jars

ALL_PIDS=()
cleanup() { kill "${ALL_PIDS[@]}" 2>/dev/null || true; }
trap cleanup EXIT

echo "[s3] starting LB + blue set (M=$M) ..."
LB_PID=$(start_lb "localhost:$BLUE1,localhost:$BLUE2" "$OUTDIR/lb.log"); ALL_PIDS+=("$LB_PID")
PB1=$(start_backend "$BLUE1" "$OUTDIR/blue1.log"); ALL_PIDS+=("$PB1")
PB2=$(start_backend "$BLUE2" "$OUTDIR/blue2.log"); ALL_PIDS+=("$PB2")
wait_port_health "$BLUE1"; wait_port_health "$BLUE2"; wait_health

T0=$(( $(now_ms) + WARMUP * 1000 ))
echo "[s3] load: λ=$RPS, warmup=${WARMUP}s, post=${POST}s, t0=$T0"
"$JAVA" -jar "$LOADGEN_JAR" url="$URL" rps="$RPS" t0EpochMs="$T0" postSec="$POST" \
    out="$OUTDIR" label="$LABEL" $LOADGEN_EXTRA > "$OUTDIR/loadgen.log" 2>&1 &
LOAD_PID=$!; ALL_PIDS+=("$LOAD_PID")

sleep_until_ms "$T0"
echo "[s3] t0 - bringing up green set"
PG1=$(start_backend "$GREEN1" "$OUTDIR/green1.log"); ALL_PIDS+=("$PG1")
PG2=$(start_backend "$GREEN2" "$OUTDIR/green2.log"); ALL_PIDS+=("$PG2")
wait_port_health "$GREEN1"; wait_port_health "$GREEN2"
if [ "$GREEN_WARMUP" -gt 0 ]; then echo "[s3] warming green ${GREEN_WARMUP}s"; sleep "$GREEN_WARMUP"; fi

echo "[s3] atomic switch blue->green"
lb_set "localhost:$GREEN1,localhost:$GREEN2"
kill -TERM "$PB1" "$PB2" 2>/dev/null || true
echo "[s3] switched; blue killed; loadgen capturing tail"

wait "$LOAD_PID"
echo "[s3] ---- summary ----"; cat "$OUTDIR/summary.json"
