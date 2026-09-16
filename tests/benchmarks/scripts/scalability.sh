#!/usr/bin/env bash
# Multi-axis scalability sweep for S0 (Live Migrator) in-process pause (RQ1 / P1 #7).
# Varies one axis at a time and exports per-axis JMH JSON to tests/benchmarks/target/scal/.
#   - m axis        : object count (V), fanout=0 (disconnected — the common case) → expect O(V)
#   - fanout axis   : references per node (connectivity), small m → exposes super-linear patch
#   - payload axis  : bytes/node (heap), V/E fixed → O(heap) walk (flat: per-object work is constant)
#   - N axis        : total live heap objects (background filler), m fixed → O(N): walk visits all objects
#   - L axis        : graph depth at fixed m/E (star=1 / chain=m / ring) → flat, stack-safe (iterative patch)
#   - gc axis       : G1 (default) / ZGC / Shenandoah / Parallel at a fixed config
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
JAR="$ROOT/tests/benchmarks/target/benchmarks.jar"
OUT="$ROOT/tests/benchmarks/target/scal"
WI="${WI:-2}"; I="${I:-5}"            # warmup / measurement iterations (single-shot)
BASEARGS=(ScalabilityBench -wi "$WI" -i "$I" -f 1 -foe true)
mkdir -p "$OUT"

[ -f "$JAR" ] || ( cd "$ROOT" && mvn -o -q -Pbenchmarks -pl tests/benchmarks package -DskipTests )

echo "=== [1/4] m axis (fanout=0, payload=64) ==="
java -jar "$JAR" "${BASEARGS[@]}" -p m=2000,10000,50000,100000 -p fanout=0 -p payloadSize=64 \
     -rf json -rff "$OUT/axis-m.json"

echo "=== [2/4] fanout axis (m=3000, payload=64) ==="
java -jar "$JAR" "${BASEARGS[@]}" -p m=3000 -p fanout=0,1,2,4 -p payloadSize=64 \
     -rf json -rff "$OUT/axis-fanout.json"

echo "=== [3/4] payloadSize axis (m=20000, fanout=0) ==="
java -jar "$JAR" "${BASEARGS[@]}" -p m=20000 -p fanout=0 -p payloadSize=16,256,1024,4096 \
     -rf json -rff "$OUT/axis-payload.json"

echo "=== [4/6] N axis (m=20000 fixed, background heap objects swept) ==="
# Total live heap N grown by background objects of a class the migrator never touches, at fixed m.
# Exposes that the filtered walk is O(total heap objects) — IterateThroughHeap visits every object.
java -jar "$JAR" "${BASEARGS[@]}" -p m=20000 -p fanout=0 -p payloadSize=64 -p bgSize=64 \
     -p bgObjects=0,500000,1000000,2000000,4000000 -p layout=ring \
     -jvmArgsAppend "-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading -Xms3g -Xmx3g" \
     -rf json -rff "$OUT/axis-N.json"

echo "=== [5/6] L axis (m=50000, E≈m fixed, depth varied: star/chain/ring) ==="
java -jar "$JAR" "${BASEARGS[@]}" -p m=50000 -p fanout=1 -p payloadSize=64 -p bgObjects=0 \
     -p layout=star,chain,ring \
     -rf json -rff "$OUT/axis-L.json"

echo "=== [6/6] GC axis (m=50000, fanout=0, payload=64) ==="
# NOTE: JMH's CLI -jvmArgsAppend REPLACES the annotation's jvmArgsAppend, so the full flag set
# (incl. -Djdk.attach.allowAttachSelf=true, needed by S0's native-agent self-attach) must be
# repeated here alongside the collector flag — otherwise the agent fails to load.
for gc in G1GC ZGC ShenandoahGC ParallelGC; do
    echo "  -- $gc --"
    java -jar "$JAR" "${BASEARGS[@]}" -p m=50000 -p fanout=0 -p payloadSize=64 \
         -jvmArgsAppend "-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading -Xms3g -Xmx3g -XX:+Use$gc" \
         -rf json -rff "$OUT/axis-gc-$gc.json" \
         || echo "  ($gc unavailable/failed, skipped)"
done

echo "=== tabulate ==="
python3 "$HERE/scal_tab.py" "$OUT"
