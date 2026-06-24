#!/usr/bin/env bash
# Twitch GraphML -> Neo4j -> RDF 1.2 -> Neo4j round-trip pipeline.
set -euo pipefail
cd "$(dirname "$0")/.."

GRAPHML="${GRAPHML:-/home/marcin/Desktop/pg_data/drive-download-20260606T111955Z-3-001/twitch/twitch-all.graphml}"
RDF_OUT="${RDF_OUT:-twitch-rdf.nt}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7691}"
export NEO4J_PASSWORD

echo "=== 1. Start Neo4j (twitch) ==="
bash scripts/start-neo4j-twitch.sh

echo ""
echo "=== 2. GraphML -> Neo4j (streaming, ~4.7M nodes, ~10M edges — may take hours) ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.GraphmlToNeo4jApp" \
  -Dexec.args="$GRAPHML"

echo ""
echo "=== 3. Neo4j -> RDF 1.2 (streaming) ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.StreamingNeo4jToRdf12App" \
  -Dexec.args="$RDF_OUT"

echo ""
echo "=== 4. Clear Neo4j before re-import ==="
# shellcheck disable=SC1091
source scripts/docker-env.sh
resolve_docker
CONTAINER="${NEO4J_TWITCH_CONTAINER:-neo4j-twitch}"
echo "Clearing via batched delete..."
"${DOCKER[@]}" exec "$CONTAINER" cypher-shell -u neo4j -p "$NEO4J_PASSWORD" --non-interactive \
  "CALL apoc.periodic.iterate('MATCH (n) RETURN id(n) AS id','MATCH (n) WHERE id(n)=id DETACH DELETE n',{batchSize:5000,parallel:false});" \
  || bash scripts/clear-neo4j-experiment.sh 2>/dev/null || true

echo ""
echo "=== 5. RDF 1.2 -> Neo4j (streaming round-trip import) ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.StreamingRdf12ToNeo4jApp" \
  -Dexec.args="$RDF_OUT"

echo ""
echo "=== 6. Neo4j -> RDF 1.2 (round-trip export) ==="
RDF_RT="${RDF_RT:-twitch-roundtrip-rdf.nt}"
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.StreamingNeo4jToRdf12App" \
  -Dexec.args="$RDF_RT"

echo ""
echo "=== 7. Semantic verification ==="
mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.CanonicalRdfVerifier" \
  -Dexec.args="$RDF_OUT $RDF_RT"

echo ""
echo "Done."
echo "  GraphML:  $GRAPHML"
echo "  RDF:      $RDF_OUT"
echo "  Neo4j:    $NEO4J_URI"
