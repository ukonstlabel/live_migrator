#!/usr/bin/env bash
# S1 — Serialize + restart under load. The service warms up, at t0 the state is serialized
# (POST /admin/dump), the process is killed and a FRESH (cold) JVM is brought up that
# deserializes the state. The cold start carries class-load + JIT re-warmup — exactly the
# recovery tail that S0 does not have.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

OUTDIR="${1:-$ROOT/tests/e2e/out/s1}"
LABEL="${LABEL:-S1}"
mkdir -p "$OUTDIR"
STATE="$OUTDIR/state.ser"
rm -f "$STATE"

build_jars

echo "[s1] starting service v1 (M=$M) ..."
"$JAVA" "${ADD_OPENS[@]}" -Dservice.initialUsers="$M" -Dservice.stateFile="$STATE" \
    -Xms"$HEAP" -Xmx"$HEAP" -jar "$SERVICE_JAR" > "$OUTDIR/service-v1.log" 2>&1 &
SVC_PID=$!
LOAD_PID=""
cleanup() { kill "$SVC_PID" ${LOAD_PID:+$LOAD_PID} 2>/dev/null || true; }
trap cleanup EXIT
wait_health

T0=$(( $(now_ms) + WARMUP * 1000 ))
echo "[s1] load: λ=$RPS, warmup=${WARMUP}s, post=${POST}s, t0=$T0"
"$JAVA" -jar "$LOADGEN_JAR" url="$URL" rps="$RPS" t0EpochMs="$T0" postSec="$POST" \
    out="$OUTDIR" label="$LABEL" $LOADGEN_EXTRA > "$OUTDIR/loadgen.log" 2>&1 &
LOAD_PID=$!

sleep_until_ms "$T0"
echo "[s1] t0 - dumping state, killing v1, starting cold v2"
curl -fsS -X POST "$URL/admin/dump" > "$OUTDIR/restart.log" 2>&1 || echo "[s1] dump failed (see restart.log)"
kill -TERM "$SVC_PID" 2>/dev/null || true
wait "$SVC_PID" 2>/dev/null || true

"$JAVA" "${ADD_OPENS[@]}" -Dservice.loadState=true -Dservice.stateFile="$STATE" \
    -Xms"$HEAP" -Xmx"$HEAP" -jar "$SERVICE_JAR" > "$OUTDIR/service-v2.log" 2>&1 &
SVC_PID=$!
echo "[s1] cold v2 starting (PID $SVC_PID) - loadgen captures downtime + warmup tail"

wait "$LOAD_PID"
echo "[s1] ---- summary ----"; cat "$OUTDIR/summary.json"
