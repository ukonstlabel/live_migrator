#!/bin/bash
set -e

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

EXAMPLES_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$EXAMPLES_DIR/.." && pwd)"
AGENT_DIR="$ROOT/agent"
OUT_LIB="$AGENT_DIR/libagent.so"

echo "ROOT = $ROOT"

# 1. Build native agent (needed for heap walking during migration)
echo "Building native agent..."
gcc -fPIC \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -shared \
    -o "${OUT_LIB}" \
    "${AGENT_DIR}/agent.c" \
    -O2

echo "Agent built: ${OUT_LIB}"

# 2. Build service-demo via reactor with examples profile
echo "Building service-demo..."
pushd "$ROOT" > /dev/null
mvn -q clean package -Pexamples -DskipTests -pl examples/service-demo -am
popd > /dev/null
JAR="$EXAMPLES_DIR/service-demo/target/service-demo-1.0.0-jar-with-dependencies.jar"
echo "Service JAR: ${JAR}"

# 3. Run service with native agent (agent is needed for heap walking when migration happens)
echo "Starting service with -agentpath=${OUT_LIB} ..."
echo "-------------------------------------------"
echo "To trigger migration, run: ./run-migration.sh <PID>"
echo "-------------------------------------------"
java \
    -agentpath:"${OUT_LIB}" \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.lang.ref=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
    --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
    -jar "${JAR}"
