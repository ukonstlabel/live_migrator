#!/usr/bin/env bash
# Builds the two probe agents used by run-jit.sh arms D/Di/E (see docs/12-jit-attribution.md).
# They deliberately live outside the Maven reactor: they must NOT end up on the service classpath,
# because the whole point is that their User implementation is loaded only at attach time.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="${1:-$ROOT/tests/e2e/target}"
SVC="$ROOT/examples/service-demo/target/service-demo-1.0.0-jar-with-dependencies.jar"
[ -f "$SVC" ] || { echo "build service-demo first: mvn -Pe2e -pl examples/service-demo -am package -DskipTests" >&2; exit 1; }

CLASSES="$(mktemp -d)"; trap 'rm -rf "$CLASSES"' EXIT
mkdir -p "$OUT"
javac --add-modules jdk.attach -cp "$SVC" -d "$CLASSES" $(find "$HERE/src" -name '*.java')

printf 'Agent-Class: probe.ClassLoadOnlyAgent\nCan-Redefine-Classes: true\n' > "$CLASSES/mf-classload.txt"
printf 'Agent-Class: probe.NoopAgent\nCan-Redefine-Classes: true\n'          > "$CLASSES/mf-noop.txt"
jar cfm "$OUT/probe-classload.jar" "$CLASSES/mf-classload.txt" -C "$CLASSES" probe
jar cfm "$OUT/probe-noop.jar"      "$CLASSES/mf-noop.txt" \
    -C "$CLASSES" probe/NoopAgent.class -C "$CLASSES" probe/ProbeLoader.class
echo "[probe] built $OUT/probe-classload.jar $OUT/probe-noop.jar"
