#!/usr/bin/env bash
# Skalowanie 1/3, 2/3, pełny dataset — oba kierunki, 5 pomiarów + 1 warmup.
#
# RDF:  wynik.nt → samples/wynik-sample-{33,67}pct.nt + wynik.nt
# PG:   pole-all.graphml → samples/pole-sample-{33,67}pct.graphml + pełny plik
#
# Use:  FRESH=1 sudo -E bash scripts/run-scaling-experiments.sh
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
FRACTIONS="${FRACTIONS:-1/3,2/3,1}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
GRAPHML="${GRAPHML:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001/pole-all.graphml}"

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker
export DOCKER_CMD
export NEO4J_PASSWORD
export NEO4J_CLEAR_MODE="${NEO4J_CLEAR_MODE:-reset}"
export NEO4J_EXPERIMENT_PLUGINS="${NEO4J_EXPERIMENT_PLUGINS:-[]}"
export NEO4J_TWITCH_PLUGINS="${NEO4J_TWITCH_PLUGINS:-[]}"

if [[ ! -f wynik.nt ]]; then
  echo "Error: wynik.nt not found in project root."
  exit 1
fi
if [[ ! -f "$GRAPHML" ]]; then
  echo "Error: GraphML not found: $GRAPHML"
  exit 1
fi

if [[ "${FRESH:-0}" == "1" ]]; then
  rm -f results/metrics_scaling_rdf.csv results/metrics_scaling_pg.csv
fi

echo "=== 1. Python venv + deps ==="
VENV="${PROJECT_VENV:-.venv-experiments}"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install -q -r python/requirements-experiments.txt

echo ""
echo "=== 2. Generate samples (fractions: ${FRACTIONS}) ==="
python python/generate_experiment_samples.py wynik.nt --fractions "$FRACTIONS"
python python/generate_graphml_samples.py "$GRAPHML" --fractions "$FRACTIONS"

RDF_SAMPLES=(
  samples/wynik-sample-33pct.nt
  samples/wynik-sample-67pct.nt
  wynik.nt
)
PG_SAMPLES=(
  samples/pole-sample-33pct.graphml
  samples/pole-sample-67pct.graphml
  "$GRAPHML"
)

echo ""
echo "=== 3. RDF12 → PG → RDF12 (neo4j-experiment :7690) ==="
echo "    Samples: ${RDF_SAMPLES[*]}"
bash scripts/start-neo4j-experiment.sh
export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7690}"
export NEO4J_EXPERIMENT_CONTAINER="${NEO4J_EXPERIMENT_CONTAINER:-neo4j-experiment}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx8g -Xss16m}"

python -u python/run_experiments.py \
  --samples "${RDF_SAMPLES[@]}" \
  --repeats "$REPEATS" \
  --warmup "$WARMUP" \
  --neo4j-uri "$NEO4J_URI" \
  --neo4j-container "$NEO4J_EXPERIMENT_CONTAINER" \
  --neo4j-clear-mode "$NEO4J_CLEAR_MODE" \
  --metrics-csv results/metrics_scaling_rdf.csv

echo ""
echo "=== 4. PG → RDF12 → PG (neo4j-twitch :7691) ==="
echo "    Samples: ${PG_SAMPLES[*]}"
bash scripts/start-neo4j-twitch.sh
export NEO4J_URI="bolt://localhost:7691"
export NEO4J_TWITCH_CONTAINER="${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"
export MAVEN_OPTS="${MAVEN_OPTS_PG:--Xmx8g -Xss16m}"

python -u python/run_pg_experiments.py \
  --samples "${PG_SAMPLES[@]}" \
  --repeats "$REPEATS" \
  --warmup "$WARMUP" \
  --neo4j-uri "$NEO4J_URI" \
  --neo4j-container "$NEO4J_TWITCH_CONTAINER" \
  --neo4j-clear-mode "$NEO4J_CLEAR_MODE" \
  --metrics-csv results/metrics_scaling_pg.csv

echo ""
echo "=== 5. Analiza + wykresy ==="
python python/analyze_experiment_metrics.py \
  --rdf-metrics results/metrics_scaling_rdf.csv \
  --pg-metrics results/metrics_scaling_pg.csv \
  --output-dir results

cp -f results/summary_rdf.md results/summary_scaling_rdf.md
cp -f results/summary_pg.md results/summary_scaling_pg.md

echo ""
echo "========== GOTOWE =========="
echo "  RDF CSV:     results/metrics_scaling_rdf.csv"
echo "  PG CSV:      results/metrics_scaling_pg.csv"
echo "  Statystyki:  results/summary_scaling_*.md"
echo "  Wykresy:     results/plots/rdf_*.png, pg_*.png, scaling_total.png, pg_scaling_total.png"
