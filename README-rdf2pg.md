# RDF 1.2 ↔ Property Graph (Neo4j)

This README focuses on **running the translator** and performing a **lossless round-trip**:

`RDF 1.2 → LPG (Neo4j) → RDF 1.2`

It is designed around YAGO-style annotated facts encoded as RDF 1.2 reification blocks:

- `_:r rdf:reifies <<( S P O )>> .`
- annotation triples on `_:r` (e.g. `schema:startDate`, `schema:endDate`, etc.)

## Requirements

### Java / Maven
- Java **17**
- Maven **3.8+**

### Neo4j
- Docker (recommended)
- Neo4j **5.x** image
- APOC enabled (optional: GraphML export)

### Python (optional)
- Python **3.10+** recommended (stdlib only for included scripts)

## Neo4j configuration

The Java apps read connection settings from:
- `neo4j.properties` (in project root), or
- env vars: `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD`

Env vars take precedence (so you can run multiple Neo4j instances without editing the file).

## Full import (`neo4j`)

```bash
mvn exec:java \
  -Dexec.mainClass="org.example.rdf2pg.RdfToPropertyGraphApp" \
  -Dexec.args="wynik.nt neo4j"
```

Input file is expected to be **RDF 1.2 N-Triples** (can start with `VERSION "1.2"`).

## Sample verification (`neo4j-sample`)

Use a separate container to keep experiments isolated from your main database.

### 1) Create a sample NT file (42 complete reification blocks)

```bash
python python/extract_sample_nt.py wynik.nt wynik-sample-42.nt --blocks 42
```

### 2) Start a sample Neo4j container

```bash
mkdir -p /home/marcin/neo4j_sample_data

sudo docker run -d --name neo4j-sample \
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
```

Browser: http://localhost:7475

### 3) Import the sample into Neo4j

```bash
NEO4J_URI=bolt://localhost:7688 \
NEO4J_PASSWORD=098e540851 \
mvn exec:java \
  -Dexec.mainClass="org.example.rdf2pg.RdfToPropertyGraphApp" \
  -Dexec.args="wynik-sample-42.nt neo4j"
```

Expected (roughly): ~42 relationships and ~60+ nodes (`Resource` + `Literal`).

### 4) Optional: export GraphML for visual inspection

In Neo4j Browser (connected to `:7688`):

```cypher
CALL apoc.export.graphml.all("sample-42.graphml", {});
```

File: `/home/marcin/neo4j_export/sample-42.graphml`

```cypher
MATCH (a)-[r:REL]->(b)
RETURN count(r) AS relCount;
// expected: 42
```

```cypher
MATCH (a:Resource)-[r:REL]->(b:Resource) RETURN count(r) AS resRes;
MATCH (a:Resource)-[r:REL]->(lit:Literal) RETURN count(r) AS resLit;
// expected: 40 and 2
```

### 5) Automated verification

```bash
# verify NT only
python3 python/verify_sample_translation.py wynik-sample-42.nt --skip-neo4j

# verify NT + Neo4j (after import to neo4j-sample)
python3 python/verify_sample_translation.py wynik-sample-42.nt

# write expected edges CSV for diffing with Neo4j exports
python3 python/verify_sample_translation.py wynik-sample-42.nt --skip-neo4j \
  --write-expected-csv expected-sample-42-edges.csv

./scripts/run-verification.sh
```

Helper scripts: `scripts/start-neo4j-sample.sh`, `scripts/import-sample-to-neo4j.sh`, `scripts/export-sample-cypher.cypher`.

`NEO4J_URI` overrides `neo4j.properties`, so importing the sample will not accidentally write into your main database.

## Reverse translation: PG → RDF 1.2

Each `REL` relationship in Neo4j becomes one RDF 1.2 reification block:

- `_:reifier rdf:reifies <<( S P O )>>`
- annotations from relationship properties (stored losslessly in the JSON property `annotations`)

Note: after updating the writer, re-import the sample into Neo4j — older databases without `annotations` are not round-trip safe.

### In-memory round-trip (no Neo4j)

```bash
mvn exec:java \
  -Dexec.mainClass="org.example.rdf2pg.RoundTripInMemoryApp" \
  -Dexec.args="wynik-sample-42.nt wynik-roundtrip-42.nt"
```

### Round-trip through neo4j-sample

```bash
./scripts/round-trip-sample.sh
```

Manual:

```bash
NEO4J_URI=bolt://localhost:7688 NEO4J_PASSWORD=098e540851 \
mvn exec:java -Dexec.mainClass="org.example.rdf2pg.PropertyGraphToRdf12App" \
  -Dexec.args="wynik-roundtrip-42.nt"

mvn exec:java -Dexec.mainClass="org.example.rdf2pg.CanonicalRdfVerifier" \
  -Dexec.args="wynik-sample-42.nt wynik-roundtrip-42.nt"
```

Expected verifier output: `OK — files are semantically equal (42 reification blocks).`

The comparison is **semantic** (same facts and annotations), not byte-for-byte — blank node IDs (`_:b...` vs `_:rt...`) may differ.

## Full dataset (later / experiments)

Run the same `PropertyGraphToRdf12App` against your full Neo4j instance (e.g. `bolt://localhost:7687`) to generate `wynik-roundtrip.nt`, then verify it with `CanonicalRdfVerifier`.

## Performance experiments (timing, statistics, plots)

Two round-trip directions are measured with **5 repetitions** (+ **1 JVM warmup** run discarded from statistics):

| Direction | Pipeline | Neo4j container | Port |
|-----------|----------|-----------------|------|
| **RDF12 → PG → RDF12** | `RdfToPropertyGraphApp` → `PropertyGraphToRdf12App` → `CanonicalRdfVerifier` | `neo4j-experiment` | 7690 |
| **PG → RDF12 → PG** | `GraphmlToNeo4jApp` → `StreamingNeo4jToRdf12App` → `StreamingRdf12ToNeo4jApp` → verify | `neo4j-twitch` | 7691 |

Timing is **wall-clock per Maven stage** for translation to/from Neo4j (includes JVM startup). Warmup runs avoid cold-start bias.

**Before each import** the Neo4j database is fully cleared (batch `DETACH DELETE` until 0 nodes and 0 relationships). This is required because the writer uses `CREATE` — leftover data from a previous sample would break verification.

### Run both directions (recommended)

```bash
GRAPHML=/path/to/pole-all.graphml bash scripts/run-all-experiments.sh

# Custom:
REPEATS=5 WARMUP=1 SIZES=42,500,1000 \
  GRAPHML=/path/to/pole-all.graphml bash scripts/run-all-experiments.sh
```

### RDF-only (YAGO samples)

```bash
bash scripts/run-experiments.sh

REPEATS=5 WARMUP=1 SIZES=42,500,1000 bash scripts/run-experiments.sh
```

### PG-only (pole GraphML)

```bash
bash scripts/start-neo4j-twitch.sh
export NEO4J_URI=bolt://localhost:7691 NEO4J_PASSWORD=098e540851
export MAVEN_OPTS=-Xmx8g

python python/run_pg_experiments.py \
  --graphml /path/to/pole-all.graphml \
  --repeats 5 --warmup 1

python python/analyze_experiment_metrics.py
```

### Output

| File | Content |
|------|---------|
| `results/metrics.csv` | RDF raw timings (import, export, verify) |
| `results/metrics_pg.csv` | PG raw timings (graphml, rdf export/import/reexport, verify) |
| `results/summary_rdf.md` | Minimal stats table (ms): mean, median, stdev, min, max per stage |
| `results/summary_pg.md` | Same for PG round-trip |
| `results/experiment_conditions.md` | Repeats, warmup, Neo4j URIs, data sizes, methodology |
| `results/summary.txt` | Legacy text summary (RDF scaling R²) |
| `results/plots/rdf_total_boxplot.png` | RDF: distribution over measured runs |
| `results/plots/rdf_stages_mean.png` | RDF: mean time per stage |
| `results/plots/pg_total_boxplot.png` | PG: distribution over measured runs |
| `results/plots/pg_stages_mean.png` | PG: mean time per stage |
| `results/plots/scaling_total.png` | RDF scaling vs block count (if multiple sizes) |

### Manual steps

```bash
python3 -m venv .venv-experiments
source .venv-experiments/bin/activate
pip install -r python/requirements-experiments.txt

python python/generate_experiment_samples.py wynik.nt --sizes 42,500,1000,2000,5000

NEO4J_URI=bolt://localhost:7690 NEO4J_PASSWORD=098e540851 \
  python python/run_experiments.py --repeats 10 --warmup 2

python python/analyze_experiment_metrics.py
```

Environment variables: `NEO4J_URI`, `NEO4J_PASSWORD`, `REPEATS`, `WARMUP`, `SIZES`, `GRAPHML`, `MAVEN_OPTS`.

## Twitch GraphML pipeline (PG → RDF 1.2 → PG)

For generic property graphs in GraphML (e.g. `twitch-all.graphml`, ~4.7M nodes, ~10M edges).

### 1) Start Neo4j (ports 7477 / 7691)

```bash
bash scripts/start-neo4j-twitch.sh
```

Browser: http://localhost:7477 — Bolt: `bolt://localhost:7691`

### 2) Manual steps

```bash
cd /home/marcin/IdeaProjects/Magisterka
export NEO4J_URI=bolt://localhost:7691
export NEO4J_PASSWORD=098e540851

# GraphML -> Neo4j
mvn exec:java -Dexec.mainClass="org.example.rdf2pg.GraphmlToNeo4jApp" \
  -Dexec.args="/path/to/twitch-all.graphml"

# Neo4j -> RDF 1.2
mvn exec:java -Dexec.mainClass="org.example.rdf2pg.StreamingNeo4jToRdf12App" \
  -Dexec.args="twitch-rdf.nt"

# Clear DB (batched), then RDF 1.2 -> Neo4j
sudo docker exec neo4j-twitch cypher-shell -u neo4j -p 098e540851 --non-interactive \
  "CALL apoc.periodic.iterate('MATCH (n) RETURN id(n) AS id','MATCH (n) WHERE id(n)=id DETACH DELETE n',{batchSize:5000,parallel:false});"

mvn exec:java -Dexec.mainClass="org.example.rdf2pg.StreamingRdf12ToNeo4jApp" \
  -Dexec.args="twitch-rdf.nt"

# Export round-trip RDF and verify
mvn exec:java -Dexec.mainClass="org.example.rdf2pg.StreamingNeo4jToRdf12App" \
  -Dexec.args="twitch-roundtrip-rdf.nt"

mvn exec:java -Dexec.mainClass="org.example.rdf2pg.CanonicalRdfVerifier" \
  -Dexec.args="twitch-rdf.nt twitch-roundtrip-rdf.nt"
```

### 3) Full pipeline script

```bash
bash scripts/twitch-round-trip.sh
```

**Note:** Full import/export may take many hours and produce a large RDF file.
