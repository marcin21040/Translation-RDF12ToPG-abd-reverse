#!/usr/bin/env bash
# Wait until Neo4j responds on HTTP or Bolt. Prints docker logs on timeout.
# Usage: wait-neo4j-ready.sh <container> [password] [http_port]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/docker-env.sh"
resolve_docker

CONTAINER="${1:?container name}"
PASSWORD="${2:-${NEO4J_PASSWORD:-098e540851}}"
HTTP_PORT="${3:-${NEO4J_EXPERIMENT_HTTP_PORT:-7476}}"
TIMEOUT_SEC="${NEO4J_STARTUP_TIMEOUT_SEC:-300}"
MAX_ITERS=$((TIMEOUT_SEC / 2))

echo "Waiting for Neo4j ($CONTAINER) — up to ${TIMEOUT_SEC}s ..."
for i in $(seq 1 "$MAX_ITERS"); do
  if curl -sf "http://localhost:${HTTP_PORT}/" >/dev/null 2>&1; then
    echo ""
    echo "Neo4j is ready (HTTP on port ${HTTP_PORT})."
    exit 0
  fi
  if "${DOCKER[@]}" exec "$CONTAINER" cypher-shell \
      -u neo4j -p "$PASSWORD" "RETURN 1" --format plain 2>/dev/null | grep -q '^1$'; then
    echo ""
    echo "Neo4j is ready (Bolt)."
    exit 0
  fi
  if (( i % 5 == 0 )); then
    printf " [%ds]" $((i * 2))
  else
    printf "."
  fi
  sleep 2
done

echo ""
echo "Warning: Neo4j did not respond within ${TIMEOUT_SEC}s."
echo "Container status:"
"${DOCKER[@]}" ps -a --filter "name=^${CONTAINER}$" --format '  {{.Names}}: {{.Status}}' 2>/dev/null || true
echo "Recent logs:"
"${DOCKER[@]}" logs --tail 50 "$CONTAINER" 2>&1 || true
exit 1
