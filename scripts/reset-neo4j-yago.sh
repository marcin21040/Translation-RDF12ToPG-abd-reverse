#!/usr/bin/env bash
# Wipe neo4j-yago completely (fast reset between full-dataset experiment runs).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/docker-env.sh"
resolve_docker

CONTAINER="${NEO4J_YAGO_CONTAINER:-neo4j-yago}"
DATA_DIR="${NEO4J_YAGO_DATA:-/home/marcin/neo4j_yago_data}"

echo "Stopping $CONTAINER ..."
"${DOCKER[@]}" stop "$CONTAINER" 2>/dev/null || true
"${DOCKER[@]}" rm "$CONTAINER" 2>/dev/null || true

echo "Wiping data directory: $DATA_DIR"
if [[ -d "$DATA_DIR" ]]; then
  if rm -rf "${DATA_DIR:?}"/* 2>/dev/null; then
    :
  else
    echo "Need sudo to remove Docker-owned files ..."
    sudo rm -rf "${DATA_DIR:?}"/*
  fi
fi
mkdir -p "$DATA_DIR"
bash "$SCRIPT_DIR/neo4j-fix-data-perms.sh" "$DATA_DIR"

echo "Recreating container ..."
export NEO4J_STARTUP_TIMEOUT_SEC="${NEO4J_STARTUP_TIMEOUT_SEC:-300}"
bash "$SCRIPT_DIR/start-neo4j-yago.sh"
