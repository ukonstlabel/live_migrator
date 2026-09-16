#!/bin/bash
set -e

# Find the service JVM process
echo "Looking for service-demo JVM process..."
PID=$(jps -l | grep "service.jar\|service-demo" | awk '{print $1}' | head -1)

if [ -z "$PID" ]; then
    echo "ERROR: Could not find service JVM process"
    echo "Running Java processes:"
    jps -l
    exit 1
fi

echo "Found service at PID: $PID"

# Copy payload to shared /tmp so the target JVM can access it
SHARED_JAR="/tmp/migration-payload.jar"
cp /app/migration-payload.jar "$SHARED_JAR"

echo "Attaching migration agent..."

java \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-modules jdk.attach \
    -cp /app/migration-payload.jar \
    migrator.load.VirtualMachineAgentLoader "$PID" "$SHARED_JAR"

echo "Migration complete!"
