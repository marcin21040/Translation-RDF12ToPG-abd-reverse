#!/usr/bin/env bash
# Tylko RDF12 → PG → RDF12: 33% / 67% / pełny yago_annotations.nt × 5 pomiarów.
# Nie rusza PG (zachowuje metrics_multi_pg.csv).
#
# Use:  REPEATS=5 WARMUP=1 sudo -E bash scripts/run-rdf-experiments-only.sh
#       FRESH_RDF=1  — usuń tylko results/metrics_multi_rdf.csv przed startem
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
FRACTIONS="${FRACTIONS:-1/3,2/3,1}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
RDF_SOURCE="${RDF_SOURCE:-yago_annotations.nt}"
METRICS_CSV="${METRICS_CSV:-results/metrics_multi_rdf.csv}"

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker
export DOCKER_CMD
export NEO4J_PASSWORD
export NEO4J_CLEAR_MODE="${NEO4J_CLEAR_MODE:-reset}"
export RDF12_INPUT_PROFILE_LENIENT="${RDF12_INPUT_PROFILE_LENIENT:-true}"
export NEO4J_EXPERIMENT_PLUGINS="${NEO4J_EXPERIMENT_PLUGINS:-[]}"

if [[ ! -f "$RDF_SOURCE" ]]; then
  echo "Error: RDF source not found: $RDF_SOURCE"
  exit 1
fi

if [[ "${FRESH_RDF:-0}" == "1" ]]; then
  rm -f "$METRICS_CSV"
  rm -f results/summary_rdf.md results/summary_multi_rdf.md
  rm -f results/plots/rdf_*.png results/plots/scaling_total.png results/plots/compare_*.png 2>/dev/null || true
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
echo "=== 2. Próbki RDF z ${RDF_SOURCE} (fractions: ${FRACTIONS}) ==="
python python/generate_experiment_samples.py "$RDF_SOURCE" --fractions "$FRACTIONS"

RDF_SAMPLES=(
  "samples/${RDF_SOURCE%.nt}-sample-33pct.nt"
  "samples/${RDF_SOURCE%.nt}-sample-67pct.nt"
  "$RDF_SOURCE"
)

echo ""
echo "=== 3. Neo4j experiment (:7690) ==="
bash scripts/start-neo4j-experiment.sh

# Nie dziedzicz NEO4J_URI z PG (twitch :7691) — sudo -E zostawia starą wartość.
unset NEO4J_URI
export NEO4J_URI="${RDF_NEO4J_URI:-bolt://localhost:7690}"
export NEO4J_EXPERIMENT_CONTAINER="${NEO4J_EXPERIMENT_CONTAINER:-neo4j-experiment}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx8g -Xss16m}"

echo "    Neo4j URI: $NEO4J_URI (container: $NEO4J_EXPERIMENT_CONTAINER)"

echo ""
echo "=== 4. RDF round-trip (${REPEATS} pomiarów + warmup ${WARMUP}) ==="
echo "    Samples: ${RDF_SAMPLES[*]}"
python -u python/run_experiments.py \
  --samples "${RDF_SAMPLES[@]}" \
  --repeats "$REPEATS" \
  --warmup "$WARMUP" \
  --neo4j-uri "$NEO4J_URI" \
  --neo4j-container "$NEO4J_EXPERIMENT_CONTAINER" \
  --neo4j-clear-mode "$NEO4J_CLEAR_MODE" \
  --metrics-csv "$METRICS_CSV"

echo ""
echo "=== 5. Analiza RDF + PG (jeśli metrics_multi_pg.csv istnieje) ==="
PG_CSV="results/metrics_multi_pg.csv"
if [[ ! -f "$PG_CSV" ]]; then
  PG_CSV="results/.no_pg_metrics"
fi
python python/analyze_experiment_metrics.py \
  --rdf-metrics "$METRICS_CSV" \
  --pg-metrics "$PG_CSV" \
  --output-dir results

cp -f results/summary_rdf.md results/summary_multi_rdf.md 2>/dev/null || true
bash scripts/fix-results-perms.sh 2>/dev/null || true

echo ""
echo "========== GOTOWE (RDF) =========="
echo "  CSV:       $METRICS_CSV"
echo "  Summary:   results/summary_rdf.md"
echo "  Wykresy:   results/plots/rdf_*.png scaling_total.png compare_*.png"
