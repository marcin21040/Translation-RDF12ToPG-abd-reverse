#!/usr/bin/env bash
# Wykresy + summary z istniejących CSV (bez ponownego importu/eksportu).
#
# Po ręcznym CanonicalRdfVerifier dla pełnego YAGO:
#   bash scripts/plot-experiment-results.sh --mark-rdf-full
#
# Tylko wykresy (CSV już ma verify_ok=ok):
#   bash scripts/plot-experiment-results.sh
set -euo pipefail
cd "$(dirname "$0")/.."

MARK_RDF_FULL=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mark-rdf-full) MARK_RDF_FULL=1; shift ;;
    *) echo "Unknown: $1"; exit 1 ;;
  esac
done

RDF_CSV="${RDF_METRICS:-results/metrics_multi_rdf.csv}"
PG_CSV="${PG_METRICS:-results/metrics_multi_pg.csv}"

bash scripts/fix-results-perms.sh 2>/dev/null || true

VENV="${PROJECT_VENV:-.venv-experiments}"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install -q -r python/requirements-experiments.txt

if [[ "$MARK_RDF_FULL" == "1" ]]; then
  python python/mark_verify_ok.py \
    --csv "$RDF_CSV" \
    --sample-file yago_annotations.nt \
    --from-status fail_verify
fi

if [[ ! -f "$PG_CSV" ]]; then
  PG_CSV="results/.no_pg_metrics"
fi

python python/analyze_experiment_metrics.py \
  --rdf-metrics "$RDF_CSV" \
  --pg-metrics "$PG_CSV" \
  --output-dir results

cp -f results/summary_rdf.md results/summary_multi_rdf.md 2>/dev/null || true
cp -f results/summary_pg.md results/summary_multi_pg.md 2>/dev/null || true

echo ""
echo "========== GOTOWE =========="
echo "  RDF CSV:   $RDF_CSV"
echo "  PG CSV:    $PG_CSV"
echo "  Summary:   results/summary_rdf.md results/summary_pg.md"
echo "  Wykresy:   results/plots/rdf_*.png pg_*.png scaling_*.png compare_*.png"
