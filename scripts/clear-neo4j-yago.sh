#!/usr/bin/env bash
# Clear neo4j-yago / neo4j-twitch (small batches + APOC for large graphs).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker
export DOCKER_CMD

CONTAINER="${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"
export NEO4J_TWITCH_CONTAINER="$CONTAINER"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
export NEO4J_CLEAR_BATCH="${NEO4J_CLEAR_BATCH:-500}"

python3 -u -c "
import os, sys
sys.path.insert(0, 'python')
from experiment_common import clear_neo4j, neo4j_stats

container = os.environ['NEO4J_TWITCH_CONTAINER']
password = os.environ['NEO4J_PASSWORD']
nodes, rels = neo4j_stats(container, password)
print(f'Nodes before clear: {nodes} (relationships: {rels})')
if nodes == 0 and rels == 0:
    print('Database already empty.')
else:
    clear_neo4j(container, password, reset_hint='scripts/reset-neo4j-experiment.sh')
    print('Done.')
"
