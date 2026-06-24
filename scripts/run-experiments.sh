#!/usr/bin/env bash
# Full experiment pipeline: Neo4j container, samples, 5 runs, plots.
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
SIZES="${SIZES:-42,500,1000,2000,5000}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7690}"
export NEO4J_PASSWORD
export NEO4J_EXPERIMENT_CONTAINER="${NEO4J_EXPERIMENT_CONTAINER:-neo4j-experiment}"
export NEO4J_CLEAR_BATCH="${NEO4J_CLEAR_BATCH:-500}"

if [[ "${FRESH:-0}" == "1" ]]; then
  rm -f results/metrics.csv
fi

if [[ "${RESET_NEO4J:-0}" == "1" ]]; then
  echo "=== 0. Reset Neo4j experiment database ==="
  bash scripts/reset-neo4j-experiment.sh
elif [[ "${SKIP_NEO4J_START:-0}" == "1" ]]; then
  echo "=== 1. Neo4j start skipped (SKIP_NEO4J_START=1) ==="
else
  echo "=== 1. Start Neo4j experiment container ==="
  bash scripts/start-neo4j-experiment.sh
fi

echo ""
echo "=== 2. Python venv + analysis deps ==="
VENV="${PROJECT_VENV:-.venv-experiments}"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install -q -r python/requirements-experiments.txt

echo ""
echo "=== 3. Generate sample files (skip: SKIP_SAMPLE_GENERATE=1) ==="
if [[ ! -f wynik.nt ]]; then
  echo "Error: wynik.nt not found in project root."
  exit 1
fi
if [[ "${SKIP_SAMPLE_GENERATE:-0}" != "1" ]]; then
  python python/generate_experiment_samples.py wynik.nt --sizes "$SIZES"
else
  echo "    Skipped (samples/ already present)"
fi

echo ""
echo "=== 4. Run RDF experiments (${REPEATS} measured + ${WARMUP} warmup per sample) ==="
echo "    Progress from Java/Maven will appear below in real time."
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g -Xss16m}"
export NEO4J_CLEAR_BATCH="${NEO4J_CLEAR_BATCH:-500}"
python -u python/run_experiments.py --repeats "$REPEATS" --warmup "$WARMUP"

echo ""
echo "=== 5. Analyze metrics and generate plots ==="
python python/analyze_experiment_metrics.py

echo ""
echo "Done. See results/summary_rdf.md and results/plots/rdf_*.png"
