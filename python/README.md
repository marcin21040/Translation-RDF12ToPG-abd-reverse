# Script: RDF-star → RDF 1.2 (N-Triples)

Conversion of YAGO files (annotated facts) from RDF-star syntax to RDF 1.2 using `rdf:reifies` and triple terms `<<( s p o )>>`.

## Usage

```bash
python rdfstar_to_rdf12.py input.ntx output.nt
```

Input: lines in YAGO format:

* meta-fact: `<<s p o>> p2 o2 .`
* regular fact: `s p o .`

Output: RDF 1.2 N-Triples with the header `VERSION "1.2"`.

Dependencies: Python standard library only (no rdflib).

## NT Sample for Translation Testing (`extract_sample_nt.py`)

Extracts **complete** reification blocks (without cutting annotations in half):

```bash
python extract_sample_nt.py output.nt output-sample-42.nt --blocks 42
```

The `output-sample-42.nt` file is intended for import into the `neo4j-sample` container (see `README-rdf2pg.md`).
