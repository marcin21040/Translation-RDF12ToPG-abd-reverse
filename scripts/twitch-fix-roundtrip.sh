#!/usr/bin/env bash
# Naprawa round-trip Twitch po poprawce escapowania \\n w literałach NT.
# Wymaga: GraphML już zaimportowany do neo4j-twitch LUB pełny import od GraphML.
set -euo pipefail
cd "$(dirname "$0")/.."

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7691}"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"
export MAVEN_OPTS="${MAVEN_OPTS_PG:--Xmx8g -Xss16m}"

echo "=== 0. Uprawnienia results/ + kompilacja ==="
bash scripts/fix-results-perms.sh
sudo rm -f results/twitch-experiment-rdf.nt results/twitch-experiment-roundtrip-rdf.nt 2>/dev/null || \
  rm -f results/twitch-experiment-rdf.nt results/twitch-experiment-roundtrip-rdf.nt
mvn -q -DskipTests compile

bash scripts/start-neo4j-twitch.sh

GRAPHML="${GRAPHML_TWITCH:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001/twitch/twitch-all.graphml}"

echo ""
echo "=== 1. GraphML -> Neo4j (pomiń jeśli baza już ma dane — ustaw SKIP_GRAPHML=1) ==="
if [[ "${SKIP_GRAPHML:-0}" != "1" ]]; then
  bash scripts/reset-neo4j-twitch.sh
  mvn -q exec:java -Dexec.mainClass=org.example.rdf2pg.GraphmlToNeo4jApp \
    -Dexec.args="$GRAPHML"
else
  echo "    SKIP_GRAPHML=1 — używam danych już w Neo4j"
fi

echo ""
echo "=== 2. Neo4j -> RDF (pierwszy eksport, z escapowaniem \\n) ==="
mvn -q exec:java -Dexec.mainClass=org.example.rdf2pg.StreamingNeo4jToRdf12App \
  -Dexec.args="results/twitch-experiment-rdf.nt"

echo ""
echo "=== 3. Sprawdzenie: brak surowych nowych linii w literałach ==="
if grep -Pn '(?<=")[^"]*\n[^"]*(?=")' results/twitch-experiment-rdf.nt 2>/dev/null | head -1; then
  echo "WARNING: nadal widać wieloliniowe literały — sprawdź kompilację"
else
  echo "    OK (grep nie znalazł wieloliniowych literałów w cudzysłowach)"
fi

echo ""
echo "=== 4. Reset + import + re-export ==="
bash scripts/reset-neo4j-twitch.sh

mvn -q exec:java -Dexec.mainClass=org.example.rdf2pg.StreamingRdf12ToNeo4jApp \
  -Dexec.args="results/twitch-experiment-rdf.nt"

mvn -q exec:java -Dexec.mainClass=org.example.rdf2pg.StreamingNeo4jToRdf12App \
  -Dexec.args="results/twitch-experiment-roundtrip-rdf.nt"

echo ""
echo "=== 5. Verify ==="
mvn -q exec:java -Dexec.mainClass=org.example.rdf2pg.CanonicalRdfVerifier \
  -Dexec.args="results/twitch-experiment-rdf.nt results/twitch-experiment-roundtrip-rdf.nt"

bash scripts/fix-results-perms.sh
echo "Done."
