# RDF 1.2 ↔ Property Graph (Neo4j) — Lossless Round-Trip

Master's thesis implementation: bidirectional translation between **RDF 1.2** (N-Triples with triple terms and `rdf:reifies`) and a **labeled property graph** in **Neo4j**, with **canonical verification** of round-trips.

Supported pipelines:

| Direction | Flow |
|-----------|------|
| **RDF → PG → RDF** | `RdfToPropertyGraphApp` → `StreamingNeo4jToRdf12App` → `CanonicalRdfVerifier` |
| **PG → RDF → PG** | `GraphmlToNeo4jApp` → `StreamingNeo4jToRdf12App` → `StreamingRdf12ToNeo4jApp` → verify |

“Lossless” means the same **semantic content** is preserved: reified triples \((S,P,O)\), annotations on reifiers, literal datatypes (with RDF normalization: plain `"lex"` ≡ `^^xsd:string`). Blank node labels (`_:b…` vs `_:rt…`) and line order may differ.

## Features

- RDF 1.2 reification blocks → Neo4j nodes (`Resource`, `Literal`) and `REL` relationships
- Streaming import/export for large graphs (YAGO-scale, Twitch ~10M edges)
- Canonical N-Triples comparator (external sort for multi-million-line files)
- Experiment harness: timing, CSV metrics, matplotlib plots
- GraphML → Neo4j → RDF round-trip for real-world LPG datasets (pole, fib25, twitch)

## Requirements

| Component | Version |
|-----------|---------|
| Java | 17 |
| Maven | 3.8+ |
| Docker | recommended (Neo4j 5.x) |
| Python | 3.10+ (experiments / plots only) |

**Maven dependencies:** Apache Jena 6.x, Neo4j Java Driver 5.26, JUnit 5.

## Quick start

### 1. Build

```bash
mvn -q compile test
```

### 2. Neo4j configuration

Copy the example file and set your password (never commit `neo4j.properties`):

```bash
cp neo4j.properties.example neo4j.properties
# edit neo4j.uri / neo4j.password
```

Or use environment variables (they override the file):

```bash
export NEO4J_URI=bolt://localhost:7690
export NEO4J_PASSWORD=your-password
```

### 3. RDF → Neo4j → RDF (single file)

Place your RDF 1.2 N-Triples file in the project root (e.g. `yago_annotations.nt`), start Neo4j, then:

```bash
bash scripts/start-neo4j-experiment.sh   # Bolt :7690

export NEO4J_URI=bolt://localhost:7690
export RDF12_INPUT_PROFILE_LENIENT=true  # required for YAGO (208 multi-term reifiers)

mvn exec:java -Dexec.mainClass=org.example.rdf2pg.RdfToPropertyGraphApp \
  -Dexec.args="yago_annotations.nt neo4j"

mvn exec:java -Dexec.mainClass=org.example.rdf2pg.StreamingNeo4jToRdf12App \
  -Dexec.args="results/yago-roundtrip.nt"

mvn exec:java -Dexec.mainClass=org.example.rdf2pg.CanonicalRdfVerifier \
  -Dexec.args="yago_annotations.nt results/yago-roundtrip.nt"
```

Expected output: `OK — files are canonically equal.`

## Experiments

### RDF scaling (33% / 67% / full YAGO)

```bash
FRESH_RDF=1 REPEATS=5 WARMUP=1 sudo -E bash scripts/run-rdf-experiments-only.sh
```

- Neo4j: `neo4j-experiment` on port **7690**
- Source: `yago_annotations.nt` (not included in repo — download separately)
- Metrics: `results/metrics_multi_rdf.csv`
- Plots: `results/plots/rdf_*.png`

### PG datasets (pole, fib25, twitch)

```bash
sudo -E bash scripts/run-multi-dataset-experiments.sh
```

- Neo4j: `neo4j-twitch` on port **7691** (16 GB RAM for Twitch)
- GraphML paths configured in `scripts/run-multi-dataset-experiments.sh`

### Regenerate plots only (no re-import)

After manual verify succeeded:

```bash
sudo chown -R $USER:$USER results/
bash scripts/plot-experiment-results.sh --mark-rdf-full
```

## Project layout

```
src/main/java/org/example/rdf2pg/   # Java translator, streaming I/O, verifier
src/test/java/                      # Compliance & round-trip tests
python/                             # Experiment runners, metrics, plots
scripts/                            # Docker Neo4j, experiment shells
docs/                               # Thesis LaTeX excerpts (Polish)
```

| Package / module | Role |
|------------------|------|
| `RdfToPropertyGraphTranslator` | RDF 1.2 → in-memory property graph |
| `PropertyGraphNeo4jWriter` | Batch UNWIND write to Neo4j |
| `StreamingNeo4jToRdf12Exporter` | Cursor-paginated Neo4j → NT |
| `StreamingRdf12ToNeo4jImporter` | Streaming NT → Neo4j |
| `CanonicalRdfVerifier` | Canonical multiset compare |
| `GraphmlStreamingImporter` | GraphML → Neo4j |

## Data files (not in repository)

Large inputs are **gitignored**. Obtain separately:

| File | Purpose |
|------|---------|
| `yago_annotations.nt` | YAGO RDF 1.2 annotations (~515 MB) |
| `samples/yago_annotations-sample-*.nt` | Generated via `python/generate_experiment_samples.py` |
| GraphML (pole, fib25, twitch) | External PG benchmark datasets |

Generate fractions from YAGO:

```bash
python python/generate_experiment_samples.py yago_annotations.nt --fractions 1/3,2/3,1
```

## Neo4j instances (default ports)

| Container | HTTP | Bolt | RAM | Use |
|-----------|------|------|-----|-----|
| `neo4j-experiment` | 7476 | 7690 | 8 GB | RDF experiments |
| `neo4j-twitch` | 7477 | 7691 | 16 GB | PG / Twitch experiments |

## RDF-star → RDF 1.2 (optional preprocessor)

```bash
python python/rdfstar_to_rdf12.py input.nt output.nt
```

## Further reading

- [`README-rdf2pg.md`](README-rdf2pg.md) — detailed workflows, troubleshooting, legacy notes
- [`python/README.md`](python/README.md) — Python utilities

## License

Academic / thesis project. Check with the author before commercial use.

## Author

Marcin Wawszczak — [github.com/marcin21040](https://github.com/marcin21040)
