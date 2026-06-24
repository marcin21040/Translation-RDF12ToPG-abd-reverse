#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

export NEO4J_URI="${NEO4J_URI:-bolt://localhost:7688}"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-098e540851}"

mvn -q exec:java \
  -Dexec.mainClass="org.example.rdf2pg.RdfToPropertyGraphApp" \
  -Dexec.args="wynik-sample-42.nt neo4j"
