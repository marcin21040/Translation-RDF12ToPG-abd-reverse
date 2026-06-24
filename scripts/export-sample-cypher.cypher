// Uruchom w Neo4j Browser na http://localhost:7475 (neo4j-sample, bolt :7688)

// Liczba relacji (oczekiwane: 42)
MATCH ()-[r:REL]->() RETURN count(r) AS relCount;

// Rozbicie Resource→Resource vs Resource→Literal (40 + 2)
MATCH (:Resource)-[r:REL]->(:Resource) RETURN count(r) AS resRes;
MATCH (:Resource)-[r:REL]->(:Literal) RETURN count(r) AS resLit;

// Eksport GraphML do /home/marcin/neo4j_export/sample-42.graphml
CALL apoc.export.graphml.all("sample-42.graphml", {});

// Podgląd do porównania z NT
MATCH (a)-[r:REL]->(b)
RETURN a.id AS source,
       r.predicate AS predicate,
       coalesce(b.id, b.value) AS target,
       r.http___schema_org_startDate AS startDate,
       r.http___schema_org_endDate AS endDate
ORDER BY source, predicate, target;
