#!/usr/bin/env bash
# PG → RDF12 → PG experiments: pole GraphML, 5 measured runs + warmup, plots.
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
GRAPHML="${GRAPHML:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001/pole-all.graphml}"

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7691}"
export NEO4J_PASSWORD
export NEO4J_TWITCH_CONTAINER="${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"
export DOCKER_CMD
export NEO4J_CLEAR_BATCH="${NEO4J_CLEAR_BATCH:-500}"

if [[ "${FRESH:-0}" == "1" ]]; then
  rm -f results/metrics_pg.csv
fi

if [[ ! -f "$GRAPHML" ]]; then
  echo "Error: GraphML not found: $GRAPHML"
  echo "Set GRAPHML=/path/to/pole-all.graphml"
  exit 1
fi

echo "=== 1. Start Neo4j twitch (PG experiments, :7691) ==="
bash scripts/start-neo4j-twitch.sh

echo ""
echo "=== 2. Python venv + deps ==="
VENV="${PROJECT_VENV:-.venv-experiments}"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install -q -r python/requirements-experiments.txt

echo ""
echo "=== 3. PG round-trip experiments (${REPEATS} measured + ${WARMUP} warmup) ==="
echo "    GraphML: $GRAPHML"
export MAVEN_OPTS="${MAVEN_OPTS_PG:--Xmx8g -Xss16m}"
python -u python/run_pg_experiments.py \
  --graphml "$GRAPHML" \
  --repeats "$REPEATS" \
  --warmup "$WARMUP"

echo ""
echo "=== 4. Analyze (RDF + PG if metrics exist) ==="
python python/analyze_experiment_metrics.py

echo ""
echo "Done. See results/summary_pg.md and results/plots/pg_*.png"
