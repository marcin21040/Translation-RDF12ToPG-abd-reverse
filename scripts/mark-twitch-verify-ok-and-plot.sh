#!/usr/bin/env bash
# Po udanym CanonicalRdfVerifier dla Twitch: oznacz 5 nocnych runów jako ok + wykresy PG.
set -euo pipefail
cd "$(dirname "$0")/.."

CSV="${1:-results/metrics_multi_pg.csv}"

bash scripts/fix-results-perms.sh 2>/dev/null || sudo chown -R "$USER:$USER" results/

VENV="${PROJECT_VENV:-.venv-experiments}"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install -q -r python/requirements-experiments.txt

python python/mark_verify_ok.py --csv "$CSV" --dataset twitch --from-status fail_verify

python python/analyze_experiment_metrics.py \
  --rdf-metrics results/.no_rdf_metrics \
  --pg-metrics "$CSV" \
  --output-dir results

cp -f results/summary_pg.md results/summary_multi_pg.md 2>/dev/null || true

echo ""
echo "========== GOTOWE =========="
echo "  CSV:      $CSV (twitch: verify_ok=ok)"
echo "  Summary:  results/summary_pg.md"
echo "  Wykresy:  results/plots/pg_*.png compare_*.png"
