# Python utilities

Scripts for RDF-star conversion, experiment samples, timing runs, and plot generation.
Experiment scripts use `python/requirements-experiments.txt` (matplotlib, numpy).

## RDF-star → RDF 1.2

```bash
python python/rdfstar_to_rdf12.py input.ntx output.nt
```

Converts YAGO-style annotated facts to RDF 1.2 N-Triples with `rdf:reifies` and triple terms `<<( s p o )>>`.
Output includes `VERSION "1.2"`. Stdlib only (no rdflib).

## Sample extraction

**By reification block count** (complete blocks, no cut annotations):

```bash
python python/extract_sample_nt.py wynik.nt wynik-sample-42.nt --blocks 42
```

**By dataset fraction** (33% / 67% / full):

```bash
python python/generate_experiment_samples.py yago_annotations.nt --fractions 1/3,2/3,1
```

Writes `samples/yago_annotations-sample-33pct.nt`, `...-67pct.nt` (full file is referenced, not copied).

## Experiment runners

Usually invoked via `scripts/`; can be run directly:

| Script | Purpose |
|--------|---------|
| `run_experiments.py` | RDF → Neo4j → RDF round-trip timing |
| `run_pg_experiments.py` | GraphML → RDF → Neo4j → RDF round-trip timing |
| `analyze_experiment_metrics.py` | CSV → summaries + `results/plots/*.png` |
| `mark_verify_ok.py` | Set `verify_ok=ok` after manual verify (`--sample-file` or `--dataset`) |

Example (RDF metrics):

```bash
python python/run_experiments.py \
  --samples samples/yago_annotations-sample-33pct.nt yago_annotations.nt \
  --repeats 5 --warmup 1 \
  --neo4j-uri bolt://localhost:7690 \
  --metrics-csv results/metrics_multi_rdf.csv
```

## Other

- `verify_sample_translation.py` — quick sample round-trip checks
- `generate_graphml_samples.py` — GraphML fraction samples for PG experiments
- `stats_report.py` — auxiliary metrics reporting
- `experiment_common.py` — shared helpers (not run directly)
