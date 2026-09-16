# Multi-stage build for Live Migrator examples
# Docker build context must be the project root (..):
#   docker build -f examples/Dockerfile ..
#
# Stage 1: Build native agent and all JARs
FROM eclipse-temurin:21-jdk AS builder

# Install build tools
RUN apt-get update && apt-get install -y \
    gcc \
    maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy source code
COPY pom.xml pom.xml
COPY migrator migrator
COPY examples examples
COPY agent agent

# Build native agent
RUN gcc -fPIC \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -shared \
    -o agent/libagent.so \
    agent/agent.c \
    -O2

# Build all modules (library + examples)
RUN mvn -q clean install -Pexamples -DskipTests

# Stage 2: Service runtime image
FROM eclipse-temurin:21-jre AS service

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy native agent and service JAR
COPY --from=builder /build/agent/libagent.so /app/libagent.so
COPY --from=builder /build/examples/service-demo/target/service-demo-1.0.0-jar-with-dependencies.jar /app/service.jar

# JVM options for migration support
ENV JAVA_OPTS="-agentpath:/app/libagent.so \
    -XX:+EnableDynamicAgentLoading \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.lang.ref=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
    --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED"

EXPOSE 8080

CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/service.jar"]

# Stage 3: Migration runtime image
FROM eclipse-temurin:21-jdk AS migrator

WORKDIR /app

# Copy migration payload JAR
COPY --from=builder /build/examples/migration-payload/target/migration-payload-1.0.0.jar /app/migration-payload.jar

# Script to find and migrate the service
COPY examples/migrate.sh /app/migrate.sh

RUN chmod +x /app/migrate.sh

CMD ["/app/migrate.sh"]
