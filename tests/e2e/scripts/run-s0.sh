#!/usr/bin/env bash
# S0 — Live Migrator under load. The service starts with the native JVMTI agent, warms up
# under open-loop load, and at t0 the migration-payload is loaded into the live JVM via
# attach — the process is NOT restarted, JIT state is preserved (no re-warmup tail).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

OUTDIR="${1:-$ROOT/tests/e2e/out/s0}"
LABEL="${LABEL:-S0}"
mkdir -p "$OUTDIR"

build_agent
build_jars

echo "[s0] starting service (M=$M, native agent) ..."
"$JAVA" -agentpath:"$AGENT_LIB" "${ADD_OPENS[@]}" \
    -Dservice.initialUsers="$M" $SERVICE_EXTRA -Xms"$HEAP" -Xmx"$HEAP" \
    -jar "$SERVICE_JAR" > "$OUTDIR/service.log" 2>&1 &
SVC_PID=$!
LOAD_PID=""
cleanup() { kill "$SVC_PID" ${LOAD_PID:+$LOAD_PID} 2>/dev/null || true; }
trap cleanup EXIT
wait_health

T0=$(( $(now_ms) + WARMUP * 1000 ))
echo "[s0] load: λ=$RPS, warmup=${WARMUP}s, post=${POST}s, t0=$T0"
"$JAVA" -jar "$LOADGEN_JAR" url="$URL" rps="$RPS" t0EpochMs="$T0" postSec="$POST" \
    out="$OUTDIR" label="$LABEL" $LOADGEN_EXTRA > "$OUTDIR/loadgen.log" 2>&1 &
LOAD_PID=$!

sleep_until_ms "$T0"
echo "[s0] t0 - triggering live migration (attach) on PID $SVC_PID"
"$JAVA" "${ADD_OPENS[@]}" --add-modules jdk.attach -cp "$PAYLOAD_JAR" \
    migrator.load.VirtualMachineAgentLoader "$SVC_PID" "$PAYLOAD_JAR" \
    > "$OUTDIR/migrate.log" 2>&1 || echo "[s0] migrate trigger returned nonzero (see migrate.log)"

wait "$LOAD_PID"
echo "[s0] ---- summary ----"; cat "$OUTDIR/summary.json"
