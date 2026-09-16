#!/usr/bin/env bash
# One-entry-point reproducibility harness (P2 #10).
#
# Builds the project and runs the evaluation suite into a single timestamped bundle with an
# environment manifest and machine-readable (JSON/CSV) artifacts.
#
#   MODE=quick ./tests/repro/reproduce.sh     # fast smoke of the whole pipeline (default; minutes)
#   MODE=full  ./tests/repro/reproduce.sh     # the full evaluation parameters (long: ~1h)
#
# Each stage is independent; a stage failure is recorded and the run continues.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MODE="${MODE:-quick}"
BUNDLE="${1:-$ROOT/tests/repro/out/$(date +%Y%m%d-%H%M%S)-$MODE}"
mkdir -p "$BUNDLE"/{jmh,scal,e2e,tests}
LOG="$BUNDLE/reproduce.log"

say() { echo "[repro] $*" | tee -a "$LOG"; }
JAR_BENCH="$ROOT/tests/benchmarks/target/benchmarks.jar"

say "MODE=$MODE  bundle=$BUNDLE"
say "1/6 environment manifest"
bash "$HERE/manifest.sh" > "$BUNDLE/manifest.json" 2>>"$LOG" || true

say "2/6 build (offline)"
( cd "$ROOT" && mvn -o -q install -pl migrator -DskipTests \
  && mvn -o -q -Pbenchmarks,e2e -pl tests/benchmarks,tests/e2e,examples/service-demo,examples/migration-payload -am \
        package -DskipTests ) >>"$LOG" 2>&1 \
  && say "  build OK" || say "  build FAILED (see log)"

say "3/6 correctness tests (conformance matrix + fault injection)"
( cd "$ROOT" && mvn -o -q -pl migrator test \
    -Dtest=ConformanceMatrixTest,FaultInjectionTest ) >>"$LOG" 2>&1 \
  && say "  tests PASSED" || say "  tests FAILED (see log)"
cp "$ROOT/migrator/target/conformance-matrix.md"  "$BUNDLE/conformance-matrix.md"  2>/dev/null || true
cp "$ROOT/migrator/target/conformance-matrix.csv" "$BUNDLE/conformance-matrix.csv" 2>/dev/null || true

say "4/6 JMH baseline (S0-S4 in-process cost)"
if [ "$MODE" = full ]; then
    JMH_ARGS=(-rf json -rff "$BUNDLE/jmh/baseline.json")
else
    JMH_ARGS=(-wi 1 -i 2 -f 1 -p m=1000,10000 -rf json -rff "$BUNDLE/jmh/baseline.json")
fi
java -jar "$JAR_BENCH" UpdateStrategyBench "${JMH_ARGS[@]}" >>"$LOG" 2>&1 \
  && say "  JMH baseline OK" || say "  JMH baseline FAILED (see log)"

say "5/6 scalability sweep (m / fanout / payload / GC)"
if [ "$MODE" = full ]; then
    bash "$ROOT/tests/benchmarks/scripts/scalability.sh" >>"$LOG" 2>&1 || say "  scalability FAILED"
else
    # tiny: prove each axis runs; not representative
    WI=1 I=2 bash -c "
      java -jar '$JAR_BENCH' ScalabilityBench -wi 1 -i 2 -f 1 -p m=2000,10000 -p fanout=0 -rf json -rff '$BUNDLE/scal/axis-m.json'
      java -jar '$JAR_BENCH' ScalabilityBench -wi 1 -i 2 -f 1 -p m=2000 -p fanout=0,1,2 -rf json -rff '$BUNDLE/scal/axis-fanout.json'
    " >>"$LOG" 2>&1 || say "  scalability FAILED"
fi
cp "$ROOT/tests/benchmarks/target/scal/"*.json "$BUNDLE/scal/" 2>/dev/null || true
python3 "$ROOT/tests/benchmarks/scripts/scal_tab.py" "$BUNDLE/scal" > "$BUNDLE/scal/summary.txt" 2>>"$LOG" || true

say "6/6 e2e load harness (downtime + JIT-warmup tail across S0-S3)"
if [ "$MODE" = full ]; then
    REPS=3 STRATEGIES="s0 s1 s2 s3" M=50000 RPS=1000 WARMUP=30 POST=60 \
      BACKEND_HEAP=1g LOADGEN_EXTRA="timeoutMs=30000 maxInflight=40000" \
      bash "$ROOT/tests/e2e/scripts/run-e2e.sh" "$BUNDLE/e2e" >>"$LOG" 2>&1 \
      && say "  e2e OK" || say "  e2e FAILED (see log)"
else
    say "  e2e skipped in quick mode (run MODE=full or tests/e2e/scripts/run-e2e.sh directly)"
fi

# ── index ────────────────────────────────────────────────────────────────
{
  echo "# Reproducibility bundle ($MODE)"
  echo
  echo "Generated: $(date -Is)"
  echo
  echo "## Contents"
  echo "- \`manifest.json\` — toolchain / hardware / git / dependency versions"
  echo "- \`conformance-matrix.{md,csv}\` — reference-coverage matrix"
  echo "- \`jmh/baseline.json\` — S0-S4 in-process cost"
  echo "- \`scal/*.json\` + \`scal/summary.txt\` — scalability axes"
  echo "- \`e2e/*/summary.json\`,\`timeline.csv\` — load-harness downtime/recovery"
  echo "- \`reproduce.log\` — full stdout"
  echo
  echo "Regenerate: \`MODE=$MODE ./tests/repro/reproduce.sh\`"
} > "$BUNDLE/INDEX.md"

say "done -> $BUNDLE"
ls -R "$BUNDLE" | head -40 | tee -a "$LOG"
