#!/bin/bash
# Run migration against the running service container
#
# Usage:
#   ./docker-migrate.sh          # Run migration
#   ./docker-migrate.sh build    # Rebuild and run migration
#
set -e

EXAMPLES_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$EXAMPLES_DIR/docker-compose.yml"

if [ "$1" = "build" ]; then
    echo "Rebuilding images..."
    docker compose -f "$COMPOSE_FILE" build
fi

echo "Starting migration container..."
docker compose -f "$COMPOSE_FILE" --profile migrate up migrator

echo "Migration container finished."
