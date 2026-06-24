#!/usr/bin/env bash
# Szybki test PG → RDF12 → PG: 5 pomiarów + 1 warmup, Neo4j 16 GB.
#
# Domyślnie 3 zbiory jak w run-multi-dataset-experiments.sh:
#   pole-all.graphml, fib25-all.graphml, twitch-all.graphml
# Tylko pole (szybki smoke test):  PG_POLE_ONLY=1 sudo -E bash scripts/run-pg-experiments-quick.sh
#
# Use:  sudo -E bash scripts/run-pg-experiments-quick.sh
#       REPEATS=5 WARMUP=1  (domyślnie)
set -euo pipefail
cd "$(dirname "$0")/.."

REPEATS="${REPEATS:-5}"
WARMUP="${WARMUP:-1}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
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
export NEO4J_TWITCH_PLUGINS="${NEO4J_TWITCH_PLUGINS:-[]}"
export NEO4J_DOCKER_MEMORY="${NEO4J_DOCKER_MEMORY:-16g}"
export NEO4J_DOCKER_MEMORY_SWAP="${NEO4J_DOCKER_MEMORY_SWAP:-16g}"
export NEO4J_HEAP_INITIAL="${NEO4J_HEAP_INITIAL:-4G}"
export NEO4J_HEAP_MAX="${NEO4J_HEAP_MAX:-6G}"
export NEO4J_PAGECACHE="${NEO4J_PAGECACHE:-8G}"
NEO4J_RECREATE="${NEO4J_RECREATE:-auto}"

echo "=== 0. Czyszczenie wyników PG (CSV, summary, wykresy, pliki .nt) ==="
rm -f results/metrics_pg.csv results/metrics_multi_pg.csv
rm -f results/summary_pg.md results/summary_multi_pg.md
rm -f results/experiment_meta_pg.json
rm -f results/*-experiment-rdf.nt results/*-experiment-roundtrip-rdf.nt
rm -f results/plots/pg_*.png results/plots/pg_scaling_total.png 2>/dev/null || true

if [[ "${PG_POLE_ONLY:-0}" == "1" ]]; then
  PG_SAMPLES=("$GRAPHML_POLE")
  METRICS_CSV="results/metrics_pg.csv"
  if [[ ! -f "$GRAPHML_POLE" ]]; then
    echo "Error: GraphML not found: $GRAPHML_POLE"
    exit 1
  fi
else
  PG_SAMPLES=("$GRAPHML_POLE" "$GRAPHML_FIB25" "$GRAPHML_TWITCH")
  METRICS_CSV="results/metrics_multi_pg.csv"
  for f in "${PG_SAMPLES[@]}"; do
    if [[ ! -f "$f" ]]; then
      echo "Error: GraphML not found: $f"
      exit 1
    fi
  done
fi

echo ""
echo "=== 1. Neo4j twitch (16 GB) — recreate jeśli stary limit pamięci ==="
if "${DOCKER[@]}" ps -a --format '{{.Names}}' | grep -qx "${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"; then
  current_mem="$("${DOCKER[@]}" inspect -f '{{.HostConfig.Memory}}' "${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}" 2>/dev/null || echo 0)"
  want_bytes=$((16 * 1024 * 1024 * 1024))
  if [[ "$NEO4J_RECREATE" == "1" ]] || [[ "$NEO4J_RECREATE" == "auto" && "$current_mem" != "$want_bytes" && "$current_mem" != "0" ]]; then
    echo "    Recreating container (memory ${current_mem} -> ${want_bytes}) ..."
    bash scripts/reset-neo4j-twitch.sh
  else
    bash scripts/start-neo4j-twitch.sh
  fi
else
  bash scripts/start-neo4j-twitch.sh
fi

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
echo "=== 3. PG round-trip: ${REPEATS} run(ów), warmup=${WARMUP} ==="
echo "    Datasets: ${PG_SAMPLES[*]}"
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
  --metrics-csv "$METRICS_CSV"

echo ""
echo "=== 4. Analiza (tylko PG) ==="
python python/analyze_experiment_metrics.py \
  --rdf-metrics results/.no_rdf_metrics \
  --pg-metrics "$METRICS_CSV" \
  --output-dir results

echo ""
echo "========== GOTOWE =========="
echo "  CSV:       $METRICS_CSV"
echo "  Summary:   results/summary_pg.md"
echo "  Wykresy:   results/plots/pg_*.png"
echo "  Zbiory:    pole → fib25 → twitch (kolejność w CSV)"
echo "  Neo4j RAM: ${NEO4J_DOCKER_MEMORY} (heap ${NEO4J_HEAP_MAX}, pagecache ${NEO4J_PAGECACHE})"
