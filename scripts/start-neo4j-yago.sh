#!/usr/bin/env bash
# Dedicated Neo4j for full YAGO wynik.nt round-trip (fresh data dir recommended).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/docker-env.sh"
resolve_docker

DATA_DIR="${NEO4J_YAGO_DATA:-/home/marcin/neo4j_yago_data}"
IMPORT_DIR="${NEO4J_EXPORT_DIR:-/home/marcin/neo4j_export}"
CONTAINER="neo4j-yago"
PASSWORD="${NEO4J_PASSWORD:-098e540851}"

mkdir -p "$DATA_DIR" "$IMPORT_DIR"
bash "$SCRIPT_DIR/neo4j-fix-data-perms.sh" "$DATA_DIR"

if "${DOCKER[@]}" ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Container $CONTAINER already exists. Starting: ${DOCKER[*]} start $CONTAINER"
  "${DOCKER[@]}" start "$CONTAINER"
else
  "${DOCKER[@]}" run -d --name "$CONTAINER" \
    -m 8g --memory-swap 8g \
    -p 7478:7474 -p 7692:7687 \
    -v "$DATA_DIR:/data" \
    -v "$IMPORT_DIR:/import" \
    -e NEO4J_AUTH="neo4j/$PASSWORD" \
    -e NEO4J_server_memory_heap_initial__size=2G \
    -e NEO4J_server_memory_heap_max__size=3G \
    -e NEO4J_server_memory_pagecache_size=3G \
    -e NEO4J_PLUGINS='["apoc"]' \
    -e NEO4J_apoc_export_file_enabled=true \
    -e NEO4J_dbms_security_procedures_unrestricted=apoc.* \
    neo4j:latest
  echo "Created $CONTAINER"
fi

echo ""
echo "Neo4j YAGO instance:"
echo "  HTTP Browser: http://localhost:7478"
echo "  Bolt URI:     bolt://localhost:7692"
echo "  User:         neo4j"
echo "  Password:     $PASSWORD"
echo ""
bash "$SCRIPT_DIR/wait-neo4j-ready.sh" "$CONTAINER" "$PASSWORD" 7478
