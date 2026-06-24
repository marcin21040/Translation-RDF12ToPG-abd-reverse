#!/usr/bin/env bash
# RDF12→PG→RDF12 — TYLKO pełny wynik.nt.
# Domyślnie 5 pomiarów + 1 warmup; szybkie czyszczenie przez reset wolumenu Docker.
#
# Use:  FRESH=1 sudo -E bash scripts/run-experiments-wynik-full.sh
# Jeden run:  REPEATS=1 WARMUP=0 FRESH=1 sudo -E bash scripts/run-experiments-wynik-full.sh
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"

# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker
export DOCKER_CMD
export NEO4J_PASSWORD
export NEO4J_CLEAR_MODE="${NEO4J_CLEAR_MODE:-reset}"
export NEO4J_EXPERIMENT_PLUGINS="${NEO4J_EXPERIMENT_PLUGINS:-[]}"

if [[ ! -f wynik.nt ]]; then
  echo "Error: wynik.nt not found in project root."
  exit 1
fi

if [[ "${FRESH:-0}" == "1" ]]; then
  rm -f results/metrics_wynik_full.csv
fi

echo "=== 1. Start Neo4j (neo4j-experiment :7690) ==="
bash scripts/start-neo4j-experiment.sh

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7690}"
export NEO4J_EXPERIMENT_CONTAINER="${NEO4J_EXPERIMENT_CONTAINER:-neo4j-experiment}"

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
echo "=== 3. wynik.nt — ${REPEATS} pomiar(ów), warmup: ${WARMUP}, clear: ${NEO4J_CLEAR_MODE} ==="
echo "    Wejście:  wynik.nt (~515 MB)"
echo "    Wyjście:  wynik-roundtrip.nt"
echo "    Metryki:  results/metrics_wynik_full.csv"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx8g -Xss16m}"

python -u python/run_experiments.py \
  --samples wynik.nt \
  --repeats "$REPEATS" \
  --warmup "$WARMUP" \
  --neo4j-uri "$NEO4J_URI" \
  --neo4j-container "$NEO4J_EXPERIMENT_CONTAINER" \
  --neo4j-clear-mode "$NEO4J_CLEAR_MODE" \
  --metrics-csv results/metrics_wynik_full.csv

echo ""
echo "=== 4. Analiza ==="
python python/analyze_experiment_metrics.py \
  --rdf-metrics results/metrics_wynik_full.csv \
  --pg-metrics results/.no_pg_metrics \
  --output-dir results

cp -f results/summary_rdf.md results/summary_wynik_full.md
cp -f results/plots/rdf_total_boxplot.png results/plots/wynik_full_boxplot.png 2>/dev/null || true
cp -f results/plots/rdf_stages_mean.png results/plots/wynik_full_stages.png 2>/dev/null || true

echo ""
echo "========== GOTOWE =========="
echo "  Metryki (CSV):     results/metrics_wynik_full.csv"
echo "  Statystyki (MD):   results/summary_wynik_full.md"
echo "  Round-trip RDF:    wynik-roundtrip.nt"
