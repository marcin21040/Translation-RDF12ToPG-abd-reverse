#!/usr/bin/env bash
# Ensure Neo4j Docker bind-mount data dir is writable by container user (uid 7474).
# Needed after wipe when scripts run under sudo and recreate the directory as root.
set -euo pipefail

DATA_DIR="${1:?data directory path}"
UID_GID="${NEO4J_DATA_UID_GID:-7474:7474}"

mkdir -p "$DATA_DIR"

if chown -R "$UID_GID" "$DATA_DIR" 2>/dev/null; then
  exit 0
fi

if sudo chown -R "$UID_GID" "$DATA_DIR" 2>/dev/null; then
  exit 0
fi

echo "Warning: could not chown $DATA_DIR to $UID_GID (Neo4j may fail to start)." >&2
