package org.example.rdf2pg;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Odczyt grafu własności z Neo4j (węzły Resource/Literal, relacje REL).
 */
public class PropertyGraphNeo4jReader implements AutoCloseable {

    private static final String NODE_LABEL = "Resource";
    private static final String LITERAL_LABEL = "Literal";
    private static final String REL_TYPE = "REL";

    private final Driver driver;

    public PropertyGraphNeo4jReader(Driver driver) {
        this.driver = driver;
    }

    public static PropertyGraphNeo4jReader create(String uri, String user, String password) {
        return new PropertyGraphNeo4jReader(Neo4jDrivers.create(uri, user, password));
    }

    public PropertyGraph read() {
        PropertyGraph graph = new PropertyGraph();
        try (var session = driver.session()) {
            List<org.neo4j.driver.Record> nodes = session.run(
                    "MATCH (n:" + NODE_LABEL + ") RETURN n.id AS id, properties(n) AS props"
            ).list();
            for (org.neo4j.driver.Record rec : nodes) {
                String id = rec.get("id").asString();
                PropertyGraphNode node = graph.getOrCreateNode(id);
                Map<String, Object> props = rec.get("props").asMap();
                Object labels = props.get(metaKeyShort(Rdf2pgMeta.NODE_LABELS));
                if (labels instanceof List<?> list) {
                    for (Object l : list) {
                        if (l != null) node.addLabel(String.valueOf(l));
                    }
                }
            }

            List<org.neo4j.driver.Record> resRels = session.run(
                    "MATCH (a:" + NODE_LABEL + ")-[r:" + REL_TYPE + "]->(b:" + NODE_LABEL + ") "
                            + "RETURN a.id AS sid, b.id AS tid, r AS rel"
            ).list();
            for (org.neo4j.driver.Record rec : resRels) {
                addEdgeFromRel(graph, rec.get("sid").asString(), rec.get("tid").asString(), rec.get("rel").asMap());
            }

            List<org.neo4j.driver.Record> litRels = session.run(
                    "MATCH (a:" + NODE_LABEL + ")-[r:" + REL_TYPE + "]->(lit:" + LITERAL_LABEL + ") "
                            + "RETURN a.id AS sid, lit.value AS val, lit.datatype AS dt, "
                            + "lit.lang AS lang, lit.direction AS dir, r AS rel"
            ).list();
            for (org.neo4j.driver.Record rec : litRels) {
                String val = rec.get("val").isNull() ? "" : rec.get("val").asString();
                String dt = Rdf2pgMeta.fromNeo4jOptional(
                        rec.get("dt").isNull() ? null : rec.get("dt").asString());
                String lang = Rdf2pgMeta.fromNeo4jOptional(
                        rec.get("lang").isNull() ? null : rec.get("lang").asString());
                String dir = Rdf2pgMeta.fromNeo4jOptional(
                        rec.get("dir").isNull() ? null : rec.get("dir").asString());
                addLiteralEdgeFromRel(graph, rec.get("sid").asString(), val, dt, lang, dir, rec.get("rel").asMap());
            }
        }
        return graph;
    }

    private static void addEdgeFromRel(PropertyGraph graph, String sid, String tid, Map<String, Object> relMap) {
        graph.getOrCreateNode(sid);
        graph.getOrCreateNode(tid);
        PropertyGraphEdge edge = buildEdge(sid, tid, relMap);
        graph.addEdge(edge);
    }

    private static void addLiteralEdgeFromRel(PropertyGraph graph, String sid, String val, String dt,
                                              String lang, String dir, Map<String, Object> relMap) {
        graph.getOrCreateNode(sid);
        PropertyGraphEdge edge = buildEdge(sid, null, relMap);
        edge.setLiteralObject(new LiteralValue(val, dt, lang, dir));
        graph.addEdge(edge);
    }

    private static PropertyGraphEdge buildEdge(String sid, String tid, Map<String, Object> relMap) {
        String predicate = stringVal(relMap.get("predicate"));
        if (predicate == null || predicate.isBlank()) {
            predicate = PgUris.RDF_TYPE;
        }
        PropertyGraphEdge edge = new PropertyGraphEdge(sid, tid, predicate);

        Object reifier = relMap.get(metaKeyShort(Rdf2pgMeta.REIFIER));
        if (reifier != null) {
            edge.setReifierId(String.valueOf(reifier));
        }
        Object asserted = relMap.get(metaKeyShort(Rdf2pgMeta.ASSERTED));
        if (asserted != null) {
            edge.setAsserted(Boolean.parseBoolean(String.valueOf(asserted)));
        }
        Object reified = relMap.get(metaKeyShort(Rdf2pgMeta.REIFIED));
        if (reified != null) {
            edge.setReified(Boolean.parseBoolean(String.valueOf(reified)));
        } else {
            edge.setReified(true);
        }

        Object annRaw = relMap.get(AnnotationJsonCodec.ANNOTATIONS_PROP);
        if (annRaw != null) {
            Map<String, Object> decoded = AnnotationJsonCodec.decode(annRaw.toString());
            decoded.forEach(edge::setProperty);
        } else {
            for (Map.Entry<String, Object> e : relMap.entrySet()) {
                String k = e.getKey();
                if (Neo4jPropertyKeys.isReservedRelKey(k)) {
                    continue;
                }
                edge.setProperty(Neo4jPropertyKeys.unsanitize(k), e.getValue());
            }
        }
        return edge;
    }

    private static String metaKeyShort(String metaUri) {
        return metaUri.substring(Rdf2pgMeta.NS.length());
    }

    private static String stringVal(Object o) {
        if (o == null) return null;
        if (o instanceof Value v) {
            return v.isNull() ? null : v.asString();
        }
        return o.toString();
    }

    @Override
    public void close() {
        driver.close();
    }
}
