#!/usr/bin/env bash
# Eksperymenty round-trip na wielu zbiorach (domyślnie 5 pomiarów + 1 warmup):
#
# RDF12 → PG → RDF12 (neo4j-experiment :7690):
#   samples/wynik-sample-33pct.nt, samples/wynik-sample-67pct.nt, yago_annotations.nt
#
# PG → RDF12 → PG (neo4j-twitch :7691, 16 GB RAM):
#   pole-all.graphml, fib25-all.graphml, twitch-all.graphml
#
# Pełny przebieg od zera:
#   FRESH=1 REPEATS=5 WARMUP=1 sudo -E bash scripts/run-multi-dataset-experiments.sh
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
FRACTIONS="${FRACTIONS:-1/3,2/3,1}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
RDF_SOURCE="${RDF_SOURCE:-yago_annotations.nt}"

PG_DATA="${PG_DATA:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001}"
GRAPHML_POLE="${GRAPHML_POLE:-$PG_DATA/pole-all.graphml}"
GRAPHML_TWITCH="${GRAPHML_TWITCH:-$PG_DATA/twitch/twitch-all.graphml}"
GRAPHML_FIB25="${GRAPHML_FIB25:-$PG_DATA/fib25/fib25-all.graphml}"

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker
export DOCKER_CMD
export NEO4J_PASSWORD
export NEO4J_CLEAR_MODE="${NEO4J_CLEAR_MODE:-reset}"
export RDF12_INPUT_PROFILE_LENIENT="${RDF12_INPUT_PROFILE_LENIENT:-true}"
export NEO4J_EXPERIMENT_PLUGINS="${NEO4J_EXPERIMENT_PLUGINS:-[]}"
export NEO4J_TWITCH_PLUGINS="${NEO4J_TWITCH_PLUGINS:-[]}"

if [[ ! -f "$RDF_SOURCE" ]]; then
  echo "Error: RDF source not found: $RDF_SOURCE"
  echo "Set RDF_SOURCE=yago_annotations.nt or path to full YAGO NT file."
  exit 1
fi
for f in "$GRAPHML_POLE" "$GRAPHML_TWITCH" "$GRAPHML_FIB25"; do
  if [[ ! -f "$f" ]]; then
    echo "Error: GraphML not found: $f"
    exit 1
  fi
done

if [[ "${FRESH:-0}" == "1" ]]; then
  rm -f results/metrics_multi_rdf.csv results/metrics_multi_pg.csv
  rm -f results/summary_rdf.md results/summary_pg.md results/summary_multi_*.md
  rm -f results/experiment_meta_rdf.json results/experiment_meta_pg.json
  rm -f results/plots/rdf_*.png results/plots/pg_*.png results/plots/scaling_*.png
  rm -f results/plots/compare_*.png 2>/dev/null || true
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
echo "=== 2. Generate RDF samples from ${RDF_SOURCE} (fractions: ${FRACTIONS}) ==="
python python/generate_experiment_samples.py "$RDF_SOURCE" --fractions "$FRACTIONS"

RDF_SAMPLES=(
  "samples/${RDF_SOURCE%.nt}-sample-33pct.nt"
  "samples/${RDF_SOURCE%.nt}-sample-67pct.nt"
  "$RDF_SOURCE"
)

PG_SAMPLES=(
  "$GRAPHML_POLE"
  "$GRAPHML_FIB25"
  "$GRAPHML_TWITCH"
)

echo ""
echo "=== 3. RDF12 → PG → RDF12 (neo4j-experiment :7690) ==="
echo "    Samples: ${RDF_SAMPLES[*]}"
if [[ "${SKIP_NEO4J_START:-0}" != "1" ]]; then
  bash scripts/start-neo4j-experiment.sh
fi
unset NEO4J_URI
export NEO4J_URI="${RDF_NEO4J_URI:-bolt://localhost:7690}"
export NEO4J_EXPERIMENT_CONTAINER="${NEO4J_EXPERIMENT_CONTAINER:-neo4j-experiment}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx8g -Xss16m}"
echo "    Neo4j URI: $NEO4J_URI"

python -u python/run_experiments.py \
  --samples "${RDF_SAMPLES[@]}" \
  --repeats "$REPEATS" \
  --warmup "$WARMUP" \
  --neo4j-uri "$NEO4J_URI" \
  --neo4j-container "$NEO4J_EXPERIMENT_CONTAINER" \
  --neo4j-clear-mode "$NEO4J_CLEAR_MODE" \
  --metrics-csv results/metrics_multi_rdf.csv

echo ""
echo "=== 4. PG → RDF12 → PG (neo4j-twitch :7691) ==="
echo "    Datasets: pole, twitch, fib25"
echo "    Files: ${PG_SAMPLES[*]}"
if [[ "${SKIP_NEO4J_START:-0}" != "1" ]]; then
  bash scripts/start-neo4j-twitch.sh
fi
export NEO4J_URI="bolt://localhost:7691"
export NEO4J_TWITCH_CONTAINER="${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"
export NEO4J_DOCKER_MEMORY="${NEO4J_DOCKER_MEMORY:-16g}"
export NEO4J_DOCKER_MEMORY_SWAP="${NEO4J_DOCKER_MEMORY_SWAP:-16g}"
export NEO4J_HEAP_INITIAL="${NEO4J_HEAP_INITIAL:-4G}"
export NEO4J_HEAP_MAX="${NEO4J_HEAP_MAX:-6G}"
export NEO4J_PAGECACHE="${NEO4J_PAGECACHE:-8G}"
export MAVEN_OPTS="${MAVEN_OPTS_PG:--Xmx8g -Xss16m}"

python -u python/run_pg_experiments.py \
  --samples "${PG_SAMPLES[@]}" \
  --repeats "$REPEATS" \
  --warmup "$WARMUP" \
  --neo4j-uri "$NEO4J_URI" \
  --neo4j-container "$NEO4J_TWITCH_CONTAINER" \
  --neo4j-clear-mode "$NEO4J_CLEAR_MODE" \
  --metrics-csv results/metrics_multi_pg.csv

echo ""
echo "=== 5. Analiza + wykresy ==="
python python/analyze_experiment_metrics.py \
  --rdf-metrics results/metrics_multi_rdf.csv \
  --pg-metrics results/metrics_multi_pg.csv \
  --output-dir results

cp -f results/summary_rdf.md results/summary_multi_rdf.md
cp -f results/summary_pg.md results/summary_multi_pg.md

bash scripts/fix-results-perms.sh 2>/dev/null || true

echo ""
echo "========== GOTOWE =========="
echo "  RDF CSV:     results/metrics_multi_rdf.csv"
echo "  PG CSV:      results/metrics_multi_pg.csv"
echo "  Statystyki:  results/summary_multi_*.md"
echo "  Wykresy:     results/plots/rdf_*.png pg_*.png compare_*.png"
echo ""
echo "Ponowny start od zera:"
echo "  FRESH=1 REPEATS=5 WARMUP=1 sudo -E bash scripts/run-multi-dataset-experiments.sh"
