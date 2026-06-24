#!/usr/bin/env bash
# Uruchamia osobny Neo4j do testów próbki (nie dotyka neo4j_backup_data).
set -euo pipefail

mkdir -p /home/marcin/neo4j_sample_data

if docker ps -a --format '{{.Names}}' | grep -qx 'neo4j-sample'; then
  echo "Kontener neo4j-sample już istnieje. Uruchamiam: docker start neo4j-sample"
  docker start neo4j-sample
  exit 0
fi

docker run -d --name neo4j-sample \
  -m 2g --memory-swap 2g \
  -p 7475:7474 -p 7688:7687 \
  -v /home/marcin/neo4j_sample_data:/data \
  -v /home/marcin/neo4j_export:/import \
  -e NEO4J_AUTH=neo4j/098e540851 \
  -e NEO4J_server_memory_heap_initial__size=512m \
  -e NEO4J_server_memory_heap_max__size=1G \
  -e NEO4J_server_memory_pagecache_size=512m \
  -e NEO4J_PLUGINS='["apoc"]' \
  -e NEO4J_apoc_export_file_enabled=true \
  -e NEO4J_dbms_security_procedures_unrestricted=apoc.* \
  neo4j:latest

echo "neo4j-sample: http://localhost:7475  bolt://localhost:7688"
echo "Poczekaj ~30s na start, potem import próbki."
