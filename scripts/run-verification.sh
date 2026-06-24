#!/usr/bin/env bash
# Pełna weryfikacja próbki: NT + opcjonalnie Neo4j (neo4j-sample na :7688)
set -euo pipefail
cd "$(dirname "$0")/.."

echo "=== 1. Weryfikacja pliku wynik-sample-42.nt ==="
python3 python/verify_sample_translation.py wynik-sample-42.nt --skip-neo4j

echo ""
echo "=== 2. Neo4j (jeśli neo4j-sample działa na :7688) ==="
if python3 python/verify_sample_translation.py wynik-sample-42.nt 2>/dev/null; then
  echo "Neo4j OK"
else
  echo "Neo4j niedostępny — uruchom: ./scripts/start-neo4j-sample.sh"
  echo "Potem: ./scripts/import-sample-to-neo4j.sh"
  echo "W Browserze: :play scripts/export-sample-cypher.cypher"
  exit 0
fi
