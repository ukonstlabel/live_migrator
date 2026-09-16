#!/bin/bash
set -e

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

EXAMPLES_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$EXAMPLES_DIR/.." && pwd)"

# Get PID from argument or try to find it
if [ -n "$1" ]; then
    PID="$1"
else
    # Try to find the service process
    PID=$(jps -l | grep "service-demo" | awk '{print $1}')
    if [ -z "$PID" ]; then
        echo "Usage: $0 <PID>"
        echo "Or start the service first with ./run-service.sh"
        echo ""
        echo "Running Java processes:"
        jps -l
        exit 1
    fi
fi

echo "Target PID: $PID"

# 1. Build all modules including examples
echo "Building project..."
pushd "$ROOT" > /dev/null
mvn -q clean install -Pexamples -DskipTests
popd > /dev/null

AGENT_JAR="$EXAMPLES_DIR/migration-payload/target/migration-payload-1.0.0.jar"
echo "Migration agent JAR: ${AGENT_JAR}"

# 2. Attach to running JVM and load the migration agent
echo "-------------------------------------------"
echo "Attaching to JVM (PID: $PID) and loading migration agent..."
echo "-------------------------------------------"

# Use Java Attach API to load agent into target JVM
java \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-modules jdk.attach \
    -cp "$AGENT_JAR" \
    migrator.load.VirtualMachineAgentLoader "$PID" "$AGENT_JAR"
