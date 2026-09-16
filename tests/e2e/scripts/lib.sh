#!/usr/bin/env bash
# Shared e2e-harness functions: JDK detection, building the native agent and jars,
# waiting for service readiness, time helpers. Included via `source`.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"

# ─── Run parameters (overridable via environment) ───
M="${M:-10000}"            # migratable state size (number of OldUser)
RPS="${RPS:-800}"          # target arrival rate λ (open-loop)
WARMUP="${WARMUP:-25}"     # seconds of load before t0 (warmup + baseline)
POST="${POST:-40}"         # seconds of load after t0 (downtime + recovery tail)
PORT="${PORT:-8080}"
HEAP="${HEAP:-2g}"
URL="http://localhost:${PORT}"
# Extra args appended to every load-generator invocation (e.g. "timeoutMs=30000 maxInflight=40000").
LOADGEN_EXTRA="${LOADGEN_EXTRA:-}"
# Extra -D flags appended to every service JVM (e.g. "-Dservice.friends=4" for reference density).
SERVICE_EXTRA="${SERVICE_EXTRA:-}"

# ─── JDK detection ───
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
fi
JAVA="$JAVA_HOME/bin/java"

AGENT_LIB="$ROOT/agent/libagent.so"
SERVICE_JAR="$ROOT/examples/service-demo/target/service-demo-1.0.0-jar-with-dependencies.jar"
PAYLOAD_JAR="$ROOT/examples/migration-payload/target/migration-payload-1.0.0.jar"
LOADGEN_JAR="$ROOT/tests/e2e/target/e2e-loadgen.jar"

ADD_OPENS=(
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-opens java.base/java.lang.ref=ALL-UNNAMED
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED
    --add-opens java.base/java.util=ALL-UNNAMED
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED
    --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED
)

now_ms() { date +%s%3N; }

sleep_until_ms() {
    local target="$1" now rem
    now="$(now_ms)"
    rem=$(( target - now ))
    if [ "$rem" -gt 0 ]; then sleep "$(awk "BEGIN{printf \"%.3f\", $rem/1000}")"; fi
}

build_agent() {
    [ "${SKIP_BUILD:-0}" = "1" ] && [ -f "$AGENT_LIB" ] && return 0
    echo "[lib] building native agent ($JAVA_HOME) ..."
    gcc -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
        -shared -O2 -o "$AGENT_LIB" "$ROOT/agent/agent.c"
}

build_jars() {
    [ "${SKIP_BUILD:-0}" = "1" ] && [ -f "$SERVICE_JAR" ] && [ -f "$LOADGEN_JAR" ] \
        && [ -f "$PAYLOAD_JAR" ] && return 0
    echo "[lib] building jars (offline) ..."
    ( cd "$ROOT" && mvn -o -q -Pe2e \
        -pl tests/e2e,examples/service-demo,examples/migration-payload -am \
        package -DskipTests )
}

wait_health() {
    local timeout="${1:-60}" deadline
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsS -o /dev/null "$URL/health" 2>/dev/null; then return 0; fi
        sleep 0.1
    done
    echo "[lib] ERROR: service did not become healthy within ${timeout}s" >&2
    return 1
}

# ─── Multi-instance helpers for S2/S3 (load balancer + backends) ───
BACKEND_HEAP="${BACKEND_HEAP:-1g}"   # per instance; up to 4 at once in S3

# start_backend PORT LOGFILE -> prints PID
start_backend() {
    local port="$1" logf="$2"
    "$JAVA" "${ADD_OPENS[@]}" -Dservice.port="$port" -Dservice.initialUsers="$M" $SERVICE_EXTRA \
        -Xms"$BACKEND_HEAP" -Xmx"$BACKEND_HEAP" -jar "$SERVICE_JAR" > "$logf" 2>&1 &
    echo $!
}

wait_port_health() {
    local port="$1" timeout="${2:-60}" deadline
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsS -o /dev/null "http://localhost:$port/health" 2>/dev/null; then return 0; fi
        sleep 0.1
    done
    echo "[lib] ERROR: backend :$port not healthy within ${timeout}s" >&2
    return 1
}

# lb_set ACTIVE_CSV  — atomically change the load balancer's active set
lb_set() { curl -fsS -X POST --data "$1" "$URL/lb/active" > /dev/null; }

start_lb() {
    local active="$1" logf="$2"
    "$JAVA" -cp "$LOADGEN_JAR" migrator.e2e.LoadBalancer \
        port="$PORT" active="$active" timeoutMs=10000 > "$logf" 2>&1 &
    echo $!
}
