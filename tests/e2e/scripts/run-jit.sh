#!/usr/bin/env bash
# JIT probe — a parameterised variant of run-s0.sh used to test whether the post-migration
# latency dip is a JIT effect (deoptimisation + recompilation after a second implementor of
# service.model.User is loaded) rather than a cost of reference patching.
#
# Same load model as S0 (open-loop, CO-corrected, t0 = attach moment). Two extra knobs:
#
#   ATTACH=migrate    real migration payload  (OldUser -> NewUser, full patch)          [default]
#   ATTACH=classload  probe agent: loads ONE new User implementor, patches nothing
#   ATTACH=noop       probe agent: loads no new User implementor at all (attach cost only)
#
#   JVM_EXTRA="..."   extra flags for the service JVM (e.g. -XX:-Inline)
#   JIT_LOG=1|0       write jit+compilation / deoptimization unified logs (default 1)
#   PROBE_DIR=...     directory holding probe-classload.jar / probe-noop.jar
#
# The service JVM's -Xlog decorators include wall-clock time, so compile/deopt events can be
# aligned with the client timeline (which is anchored to the same absolute t0).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

OUTDIR="${1:-$ROOT/tests/e2e/out/jit}"
LABEL="${LABEL:-JIT}"
ATTACH="${ATTACH:-migrate}"
JVM_EXTRA="${JVM_EXTRA:-}"
JIT_LOG="${JIT_LOG:-1}"
PROBE_DIR="${PROBE_DIR:-$ROOT/tests/e2e/target}"
mkdir -p "$OUTDIR"

build_agent
build_jars
if [ "$ATTACH" != "migrate" ] && [ ! -f "$PROBE_DIR/probe-classload.jar" ]; then
    "$ROOT/tests/e2e/probe/build.sh" "$PROBE_DIR"
fi

LOG_FLAGS=()
if [ "$JIT_LOG" = "1" ]; then
    LOG_FLAGS=(
        -XX:+UnlockDiagnosticVMOptions
        "-Xlog:jit+compilation=debug:file=$OUTDIR/compilation.log:time,uptime"
        "-Xlog:deoptimization=debug:file=$OUTDIR/deoptimization.log:time,uptime"
        "-Xlog:class+load=info:file=$OUTDIR/classload.log:time,uptime"
        "-Xlog:gc:file=$OUTDIR/gc.log:time,uptime"
    )
fi

echo "[jit] starting service (M=$M, attach=$ATTACH, jit_log=$JIT_LOG, extra='$JVM_EXTRA') ..."
"$JAVA" -agentpath:"$AGENT_LIB" "${ADD_OPENS[@]}" "${LOG_FLAGS[@]}" $JVM_EXTRA \
    -Dservice.initialUsers="$M" $SERVICE_EXTRA -Xms"$HEAP" -Xmx"$HEAP" \
    -jar "$SERVICE_JAR" > "$OUTDIR/service.log" 2>&1 &
SVC_PID=$!
LOAD_PID=""
cleanup() { kill "$SVC_PID" ${LOAD_PID:+$LOAD_PID} 2>/dev/null || true; }
trap cleanup EXIT
wait_health

T0=$(( $(now_ms) + WARMUP * 1000 ))
echo "[jit] load: λ=$RPS, warmup=${WARMUP}s, post=${POST}s, t0=$T0"
echo "$T0" > "$OUTDIR/t0.txt"
"$JAVA" -jar "$LOADGEN_JAR" url="$URL" rps="$RPS" t0EpochMs="$T0" postSec="$POST" \
    out="$OUTDIR" label="$LABEL" $LOADGEN_EXTRA > "$OUTDIR/loadgen.log" 2>&1 &
LOAD_PID=$!

sleep_until_ms "$T0"
case "$ATTACH" in
    migrate)
        echo "[jit] t0 — live migration (attach) on PID $SVC_PID"
        "$JAVA" "${ADD_OPENS[@]}" --add-modules jdk.attach -cp "$PAYLOAD_JAR" \
            migrator.load.VirtualMachineAgentLoader "$SVC_PID" "$PAYLOAD_JAR" \
            > "$OUTDIR/attach.log" 2>&1 || echo "[jit] attach returned nonzero (see attach.log)" ;;
    classload)
        echo "[jit] t0 — class-load-only probe (attach, insert=${INSERT:-0}) on PID $SVC_PID"
        "$JAVA" --add-modules jdk.attach -cp "$PROBE_DIR/probe-classload.jar" \
            probe.ProbeLoader "$SVC_PID" "$PROBE_DIR/probe-classload.jar" "insert=${INSERT:-0}" \
            > "$OUTDIR/attach.log" 2>&1 || echo "[jit] attach returned nonzero (see attach.log)" ;;
    noop)
        echo "[jit] t0 — no-op probe (attach) on PID $SVC_PID"
        "$JAVA" --add-modules jdk.attach -cp "$PROBE_DIR/probe-noop.jar" \
            probe.ProbeLoader "$SVC_PID" "$PROBE_DIR/probe-noop.jar" "" \
            > "$OUTDIR/attach.log" 2>&1 || echo "[jit] attach returned nonzero (see attach.log)" ;;
    *) echo "[jit] unknown ATTACH=$ATTACH" >&2; exit 2 ;;
esac

wait "$LOAD_PID"
echo "[jit] ---- summary ----"; cat "$OUTDIR/summary.json"
