#!/usr/bin/env bash
# Dedicated Neo4j container for performance / round-trip experiments.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/docker-env.sh"
resolve_docker

DATA_DIR="${NEO4J_EXPERIMENT_DATA:-/home/marcin/neo4j_experiment_data}"
IMPORT_DIR="${NEO4J_EXPORT_DIR:-/home/marcin/neo4j_export}"
CONTAINER="neo4j-experiment"
PASSWORD="${NEO4J_PASSWORD:-098e540851}"
PLUGINS="${NEO4J_EXPERIMENT_PLUGINS:-[]}"
IMAGE="${NEO4J_EXPERIMENT_IMAGE:-neo4j:latest}"

mkdir -p "$DATA_DIR" "$IMPORT_DIR"
bash "$SCRIPT_DIR/neo4j-fix-data-perms.sh" "$DATA_DIR"

if "${DOCKER[@]}" ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Container $CONTAINER already exists. Starting: ${DOCKER[*]} start $CONTAINER"
  "${DOCKER[@]}" start "$CONTAINER"
else
  RUN_ARGS=(
    run -d --name "$CONTAINER"
    -m 8g --memory-swap 8g
    -p 7476:7474 -p 7690:7687
    -v "$DATA_DIR:/data"
    -v "$IMPORT_DIR:/import"
    -e "NEO4J_AUTH=neo4j/$PASSWORD"
    -e NEO4J_server_memory_heap_initial__size=2G
    -e NEO4J_server_memory_heap_max__size=3G
    -e NEO4J_server_memory_pagecache_size=3G
    -e "NEO4J_PLUGINS=$PLUGINS"
  )
  if [[ "$PLUGINS" != "[]" && -n "$PLUGINS" ]]; then
    RUN_ARGS+=(
      -e NEO4J_apoc_export_file_enabled=true
      -e NEO4J_dbms_security_procedures_unrestricted=apoc.*
    )
  fi
  "${DOCKER[@]}" "${RUN_ARGS[@]}" "$IMAGE"
  echo "Created $CONTAINER (plugins=$PLUGINS)"
fi

echo ""
echo "Neo4j experiment instance:"
echo "  HTTP Browser: http://localhost:7476"
echo "  Bolt URI:     bolt://localhost:7690"
echo "  User:         neo4j"
echo "  Password:     $PASSWORD"
echo ""
bash "$SCRIPT_DIR/wait-neo4j-ready.sh" "$CONTAINER" "$PASSWORD" "${NEO4J_EXPERIMENT_HTTP_PORT:-7476}"
