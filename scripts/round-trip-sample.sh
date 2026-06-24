#!/usr/bin/env bash
# Pełny round-trip na próbce: NT → Neo4j → NT → porównanie semantyczne.
set -euo pipefail
cd "$(dirname "$0")/.."

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7688}"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"

echo "=== 1. Import wynik-sample-42.nt do neo4j-sample ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.RdfToPropertyGraphApp" \
  -Dexec.args="wynik-sample-42.nt neo4j"

echo ""
echo "=== 2. Eksport PG → RDF 1.2 (wynik-roundtrip-42.nt) ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.PropertyGraphToRdf12App" \
  -Dexec.args="wynik-roundtrip-42.nt"

echo ""
echo "=== 3. Porównanie semantyczne ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.CanonicalRdfVerifier" \
  -Dexec.args="wynik-sample-42.nt wynik-roundtrip-42.nt"
