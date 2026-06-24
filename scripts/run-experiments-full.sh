#!/usr/bin/env bash
# RDF12↔PG then PG↔RDF12 experiments (5 runs each) + analysis.
# Use sudo if docker requires it:  sudo -E bash scripts/run-experiments-full.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
export REPEATS="${REPEATS:-5}"
export WARMUP="${WARMUP:-1}"
export GRAPHML="${GRAPHML:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001/pole-all.graphml}"

echo "========== Part 1: RDF12 → PG → RDF12 =========="
bash scripts/run-experiments.sh

echo ""
echo "========== Part 2: PG → RDF12 → PG =========="
bash scripts/run-pg-experiments.sh

echo ""
echo "All experiments finished."
echo "  results/summary_rdf.md"
echo "  results/summary_pg.md"
echo "  results/experiment_conditions.md"
echo "  results/plots/"
