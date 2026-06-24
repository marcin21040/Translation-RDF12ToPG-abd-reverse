#!/usr/bin/env bash
# Neo4j container for Twitch / pole GraphML experiments.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/docker-env.sh"
resolve_docker

CONTAINER="${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"
DATA_DIR="${NEO4J_TWITCH_DATA:-/home/marcin/neo4j_twitch_data}"
IMPORT_DIR="${NEO4J_TWITCH_IMPORT:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001/twitch}"
PASSWORD="${NEO4J_PASSWORD:-098e540851}"
PLUGINS="${NEO4J_TWITCH_PLUGINS:-[]}"
IMAGE="${NEO4J_TWITCH_IMAGE:-neo4j:latest}"
DOCKER_MEMORY="${NEO4J_DOCKER_MEMORY:-16g}"
DOCKER_MEMORY_SWAP="${NEO4J_DOCKER_MEMORY_SWAP:-16g}"
HEAP_INITIAL="${NEO4J_HEAP_INITIAL:-4G}"
HEAP_MAX="${NEO4J_HEAP_MAX:-6G}"
PAGECACHE="${NEO4J_PAGECACHE:-8G}"

mkdir -p "$DATA_DIR" "$IMPORT_DIR"
bash "$SCRIPT_DIR/neo4j-fix-data-perms.sh" "$DATA_DIR"

if "${DOCKER[@]}" ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Container $CONTAINER exists. Starting..."
  "${DOCKER[@]}" start "$CONTAINER"
else
  RUN_ARGS=(
    run -d --name "$CONTAINER"
    -m "$DOCKER_MEMORY" --memory-swap "$DOCKER_MEMORY_SWAP"
    -p 7477:7474 -p 7691:7687
    -v "$DATA_DIR:/data"
    -v "$IMPORT_DIR:/import"
    -e "NEO4J_AUTH=neo4j/$PASSWORD"
    -e "NEO4J_server_memory_heap_initial__size=$HEAP_INITIAL"
    -e "NEO4J_server_memory_heap_max__size=$HEAP_MAX"
    -e "NEO4J_server_memory_pagecache_size=$PAGECACHE"
    -e "NEO4J_PLUGINS=$PLUGINS"
  )
  if [[ "$PLUGINS" != "[]" && -n "$PLUGINS" ]]; then
    RUN_ARGS+=(
      -e NEO4J_apoc_export_file_enabled=true
      -e NEO4J_dbms_security_procedures_unrestricted=apoc.*
    )
  fi
  "${DOCKER[@]}" "${RUN_ARGS[@]}" "$IMAGE"
  echo "Created $CONTAINER (memory=$DOCKER_MEMORY heap=$HEAP_MAX pagecache=$PAGECACHE plugins=$PLUGINS)"
fi

echo ""
echo "Neo4j Twitch instance:"
echo "  Browser:  http://localhost:7477"
echo "  Bolt:     bolt://localhost:7691"
echo "  User:     neo4j"
echo "  Password: $PASSWORD"
echo ""
bash "$SCRIPT_DIR/wait-neo4j-ready.sh" "$CONTAINER" "$PASSWORD" 7477
