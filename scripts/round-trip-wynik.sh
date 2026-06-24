#!/usr/bin/env bash
# Full YAGO round-trip on fresh neo4j-yago: wynik.nt → Neo4j → wynik-roundtrip.nt → verify
set -euo pipefail
cd "$(dirname "$0")/.."

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7692}"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx8g -Xss16m}"

INPUT="${1:-wynik.nt}"
OUTPUT="${2:-wynik-roundtrip.nt}"

if [[ ! -f "$INPUT" ]]; then
  echo "Input file not found: $INPUT"
  exit 1
fi

echo "=== 1. Start neo4j-yago container ==="
bash scripts/start-neo4j-yago.sh

echo ""
echo "=== 2. Clear database ==="
bash scripts/clear-neo4j-yago.sh

echo ""
echo "=== 3. Import $INPUT → Neo4j ($NEO4J_URI) ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.RdfToPropertyGraphApp" \
  -Dexec.args="$INPUT"

echo ""
echo "=== 4. Export Neo4j → $OUTPUT ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.PropertyGraphToRdf12App" \
  -Dexec.args="$OUTPUT"

echo ""
echo "=== 5. Canonical verification ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.CanonicalRdfVerifier" \
  -Dexec.args="$INPUT $OUTPUT"

echo ""
echo "Round-trip complete: $INPUT ↔ $OUTPUT"
