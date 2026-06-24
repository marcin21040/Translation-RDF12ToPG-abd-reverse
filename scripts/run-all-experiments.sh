#!/usr/bin/env bash
# Full experiment pipeline: RDF12↔PG round-trips, 5 measured runs + warmup, stats + plots.
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
SIZES="${SIZES:-42,500,1000,2000,5000}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
GRAPHML="${GRAPHML:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001/pole-all.graphml}"

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker

export DOCKER_CMD
export NEO4J_PASSWORD

if [[ "${RESET_NEO4J:-0}" == "1" ]]; then
  echo "=== 0. Reset Neo4j experiment database ==="
  bash scripts/reset-neo4j-experiment.sh
fi

echo "=== 1. Python venv + analysis deps ==="
VENV="${PROJECT_VENV:-.venv-experiments}"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install -q -r python/requirements-experiments.txt

echo ""
echo "=== 2. RDF12 → PG → RDF12 (neo4j-experiment :7690) ==="
export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7690}"
export NEO4J_EXPERIMENT_CONTAINER="${NEO4J_EXPERIMENT_CONTAINER:-neo4j-experiment}"

if [[ "${SKIP_NEO4J_START:-0}" != "1" ]]; then
  bash scripts/start-neo4j-experiment.sh
fi

if [[ ! -f wynik.nt ]]; then
  echo "Error: wynik.nt not found in project root."
  exit 1
fi

python python/generate_experiment_samples.py wynik.nt --sizes "$SIZES"

export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g -Xss16m}"
python -u python/run_experiments.py --repeats "$REPEATS" --warmup "$WARMUP"

echo ""
echo "=== 3. PG → RDF12 → PG (neo4j-twitch :7691, pole-all.graphml) ==="

if [[ -z "$GRAPHML" ]]; then
  echo "Error: set GRAPHML to path of pole-all.graphml"
  echo "  Example: GRAPHML=/path/to/pole-all.graphml bash scripts/run-all-experiments.sh"
  exit 1
fi

if [[ ! -f "$GRAPHML" ]]; then
  echo "Error: GraphML not found: $GRAPHML"
  exit 1
fi

export NEO4J_URI="bolt://localhost:7691"
export NEO4J_TWITCH_CONTAINER="${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"
bash scripts/start-neo4j-twitch.sh

export MAVEN_OPTS="${MAVEN_OPTS_PG:--Xmx8g -Xss16m}"
python -u python/run_pg_experiments.py \
  --graphml "$GRAPHML" \
  --repeats "$REPEATS" \
  --warmup "$WARMUP"

echo ""
echo "=== 4. Analyze metrics and generate plots ==="
python python/analyze_experiment_metrics.py

echo ""
echo "Done. See:"
echo "  results/summary_rdf.md"
echo "  results/summary_pg.md"
echo "  results/experiment_conditions.md"
echo "  results/plots/"
