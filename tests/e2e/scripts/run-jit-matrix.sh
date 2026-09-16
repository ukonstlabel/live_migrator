#!/usr/bin/env bash
# Runs the JIT-attribution matrix. Each arm answers one question about the post-migration dip.
#
#   A    real migration, instrumented                        — is there a deopt burst at t0?
#   A0   real migration, no JIT logging                      — control for logging overhead
#   B    real migration, -XX:-Inline                         — kill inlining: does the dip go?
#   Cbi  real migration, call site pre-warmed bimorphic      — CHA already dead before t0
#   Cmg  real migration, call site pre-warmed megamorphic    — C2 gives up inlining that site
#   D    class-load-only probe (no patching at all)          — dip with zero migration logic?
#   Di   class-load probe + M/5 instances into the hot list  — type-profile pollution, no patching
#   Di1  class-load probe + ONE instance into the hot list  — same deopt, no list-growth confound
#   E    no-op attach (no new User implementor)              — attach cost alone
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

REPS="${REPS:-3}"
ARMS="${ARMS:-A A0 B Cbi Cmg F D Di Di1 G E}"
BASE="${1:-$ROOT/tests/e2e/out/jit-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$BASE"

build_agent; build_jars
export SKIP_BUILD=1

run_arm() {  # run_arm NAME OUTDIR
    local name="$1" out="$2"
    case "$name" in
        A)   ATTACH=migrate   JIT_LOG=1 JVM_EXTRA=""            SERVICE_EXTRA="" ;;
        A0)  ATTACH=migrate   JIT_LOG=0 JVM_EXTRA=""            SERVICE_EXTRA="" ;;
        B)   ATTACH=migrate   JIT_LOG=1 JVM_EXTRA="-XX:-Inline" SERVICE_EXTRA="" ;;
        Cbi) ATTACH=migrate   JIT_LOG=1 JVM_EXTRA=""            SERVICE_EXTRA="-Dservice.prewarm=bi" ;;
        Cmg) ATTACH=migrate   JIT_LOG=1 JVM_EXTRA=""            SERVICE_EXTRA="-Dservice.prewarm=mega" ;;
        D)   ATTACH=classload JIT_LOG=1 JVM_EXTRA=""            SERVICE_EXTRA="" INSERT=0 ;;
        Di)  ATTACH=classload JIT_LOG=1 JVM_EXTRA=""            SERVICE_EXTRA="" INSERT=$((M/5)) ;;
        Di1) ATTACH=classload JIT_LOG=1 JVM_EXTRA=""            SERVICE_EXTRA="" INSERT=1 ;;
        E)   ATTACH=noop      JIT_LOG=1 JVM_EXTRA=""            SERVICE_EXTRA="" ;;
        F)   ATTACH=migrate   JIT_LOG=1 JVM_EXTRA="-XX:TypeProfileWidth=0" SERVICE_EXTRA="" ;;
        G)   ATTACH=classload JIT_LOG=1 JVM_EXTRA="-XX:TypeProfileWidth=0" SERVICE_EXTRA="" INSERT=$((M/5)) ;;
        *)   echo "unknown arm $name" >&2; return 2 ;;
    esac
    export ATTACH JIT_LOG JVM_EXTRA SERVICE_EXTRA
    export INSERT="${INSERT:-0}"
    LABEL="$name" "$HERE/run-jit.sh" "$out" > "$out.driver.log" 2>&1
}

echo "=== jit matrix: M=$M RPS=$RPS WARMUP=${WARMUP}s POST=${POST}s REPS=$REPS -> $BASE ==="
for r in $(seq 1 "$REPS"); do
    for a in $ARMS; do
        out="$BASE/${a}-rep${r}"; mkdir -p "$out"
        echo ">>> [$(date +%H:%M:%S)] arm $a replica $r"
        run_arm "$a" "$out" || echo "!!! $a rep$r failed (see $out.driver.log)"
        unset INSERT
        sleep 3
    done
done
echo "=== done: $BASE ==="
