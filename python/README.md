# Skrypt: RDF-star → RDF 1.2 (N-Triples)

Konwersja plików YAGO (annotated facts) ze składni RDF-star na RDF 1.2 z `rdf:reifies` i triple term `<<( s p o )>>`.

## Użycie

```bash
python rdfstar_to_rdf12.py wejście.ntx wyjście.nt
```

Wejście: linie w formacie YAGO:

- meta-fakt: `<<s p o>> p2 o2 .`
- zwykły fakt: `s p o .`

Wyjście: N-Triples RDF 1.2 z nagłówkiem `VERSION "1.2"`.

Zależności: tylko biblioteka standardowa Pythona (bez rdflib).

## Próbka NT do testów translacji (`extract_sample_nt.py`)

Wyciąga **kompletne** bloki reifikacji (nie ucięte w połowie annotacji):

```bash
python extract_sample_nt.py wynik.nt wynik-sample-42.nt --blocks 42
```

Plik `wynik-sample-42.nt` służy do importu w kontenerze `neo4j-sample` (patrz `README-rdf2pg.md`).
