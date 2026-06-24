package org.example.rdf2pg;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Zapis grafu własności do Neo4j — zoptymalizowany pod duże zbiory.
 */
public class PropertyGraphNeo4jWriter implements AutoCloseable {

    private static final String NODE_LABEL = "Resource";
    private static final String LITERAL_LABEL = "Literal";
    private static final String REL_TYPE = "REL";

    private static final int UNWIND_BATCH = 10_000;

    private final Driver driver;
    private final Consumer<String> progressCallback;

    public PropertyGraphNeo4jWriter(Driver driver) {
        this(driver, null);
    }

    public PropertyGraphNeo4jWriter(Driver driver, Consumer<String> progressCallback) {
        this.driver = driver;
        this.progressCallback = progressCallback != null ? progressCallback : (msg) -> {};
    }

    public static PropertyGraphNeo4jWriter create(String uri, String user, String password) {
        return create(uri, user, password, null);
    }

    public static PropertyGraphNeo4jWriter create(String uri, String user, String password, Consumer<String> progressCallback) {
        Driver driver = Neo4jDrivers.create(uri, user, password);
        return new PropertyGraphNeo4jWriter(driver, progressCallback);
    }

    public void write(PropertyGraph graph) {
        int totalNodes = graph.getNodes().size();
        int totalEdges = graph.getEdges().size();
        report("Zapis węzłów: 0 / " + totalNodes + " (najpierw indeksy, potem batch co " + UNWIND_BATCH + ")");

        try (var session = driver.session()) {
            report("Tworzenie indeksów Neo4j…");
            session.executeWrite(tx -> {
                tx.run("CREATE INDEX resource_id IF NOT EXISTS FOR (n:" + NODE_LABEL + ") ON (n.id)");
                tx.run("CREATE INDEX literal_value IF NOT EXISTS FOR (n:" + LITERAL_LABEL + ") ON (n.value)");
                return null;
            });
            Neo4jIndexHelper.awaitIndexesBestEffort(session, this::report);
            report("MERGE węzłów (postęp co " + UNWIND_BATCH + ")");

            List<PropertyGraphNode> nodeList = new ArrayList<>(graph.getNodes().values());
            int doneNodes = 0;
            List<PropertyGraphNode> buf = new ArrayList<>(UNWIND_BATCH);
            for (PropertyGraphNode n : nodeList) {
                buf.add(n);
                if (buf.size() >= UNWIND_BATCH) {
                    final List<PropertyGraphNode> batch = new ArrayList<>(buf);
                    session.executeWrite(tx -> {
                        mergeNodesUnwindOnce(tx, batch);
                        return null;
                    });
                    doneNodes += batch.size();
                    buf.clear();
                    report("Zapis węzłów: " + doneNodes + " / " + totalNodes);
                }
            }
            if (!buf.isEmpty()) {
                final List<PropertyGraphNode> tail = new ArrayList<>(buf);
                session.executeWrite(tx -> {
                    mergeNodesUnwindOnce(tx, tail);
                    return null;
                });
                doneNodes += tail.size();
                report("Zapis węzłów: " + doneNodes + " / " + totalNodes);
            }

            report("Zapis krawędzi: 0 / " + totalEdges);
            List<PropertyGraphEdge> edgeList = graph.getEdges();
            List<PropertyGraphEdge> resEdges = new ArrayList<>();
            List<PropertyGraphEdge> litEdges = new ArrayList<>();
            for (PropertyGraphEdge e : edgeList) {
                if (e.isLiteralEdge()) {
                    litEdges.add(e);
                } else {
                    resEdges.add(e);
                }
            }

            int doneEdges = 0;
            doneEdges += flushEdgesInChunks(session, resEdges, true, doneEdges, totalEdges);
            doneEdges += flushEdgesInChunks(session, litEdges, false, doneEdges, totalEdges);
        } catch (org.neo4j.driver.exceptions.Neo4jException e) {
            report("Błąd Neo4j: " + e.getMessage());
            throw new RuntimeException("Zapis do Neo4j nie powiódł się: " + e.getMessage(), e);
        } catch (Exception e) {
            report("Błąd: " + e.getMessage());
            throw new RuntimeException("Zapis do Neo4j nie powiódł się.", e);
        }
    }

    private int flushEdgesInChunks(org.neo4j.driver.Session session, List<PropertyGraphEdge> edges,
                                   boolean resourceTarget, int doneSoFar, int totalEdges) {
        int done = 0;
        for (int from = 0; from < edges.size(); from += UNWIND_BATCH) {
            int to = Math.min(from + UNWIND_BATCH, edges.size());
            List<PropertyGraphEdge> chunk = edges.subList(from, to);
            session.executeWrite(tx -> {
                if (resourceTarget) {
                    unwindResourceEdges(tx, chunk);
                } else {
                    unwindLiteralEdges(tx, chunk);
                }
                return null;
            });
            done += chunk.size();
            report("Zapis krawędzi: " + (doneSoFar + done) + " / " + totalEdges);
        }
        return done;
    }

    private static void mergeNodesUnwindOnce(org.neo4j.driver.TransactionContext tx, List<PropertyGraphNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (PropertyGraphNode n : nodes) {
            Map<String, Object> row = new HashMap<>(2);
            row.put("id", n.getId());
            Map<String, Object> props = new HashMap<>();
            for (Map.Entry<String, Object> e : n.getProperties().entrySet()) {
                props.put(sanitizePropKey(e.getKey()), toNeo4jValue(e.getValue()));
            }
            if (!n.getLabels().isEmpty()) {
                props.put(metaKeyShort(Rdf2pgMeta.NODE_LABELS), new ArrayList<>(n.getLabels()));
            }
            row.put("props", props);
            rows.add(row);
        }
        tx.run(
                "UNWIND $rows AS row "
                        + "MERGE (n:" + NODE_LABEL + " {id: row.id}) "
                        + "SET n += row.props",
                Map.of("rows", rows));
    }

    private static void unwindResourceEdges(org.neo4j.driver.TransactionContext tx, List<PropertyGraphEdge> chunk) {
        List<Map<String, Object>> rows = new ArrayList<>(chunk.size());
        for (PropertyGraphEdge e : chunk) {
            Map<String, Object> row = new HashMap<>(4);
            row.put("sid", e.getSourceId());
            row.put("tid", e.getTargetId());
            row.put("props", relPropsMap(e));
            rows.add(row);
        }
        tx.run(
                "UNWIND $rows AS row "
                        + "MATCH (a:" + NODE_LABEL + " {id: row.sid}), (b:" + NODE_LABEL + " {id: row.tid}) "
                        + "CREATE (a)-[r:" + REL_TYPE + "]->(b) SET r += row.props",
                Map.of("rows", rows));
    }

    private static void unwindLiteralEdges(org.neo4j.driver.TransactionContext tx, List<PropertyGraphEdge> chunk) {
        List<Map<String, Object>> rows = new ArrayList<>(chunk.size());
        for (PropertyGraphEdge e : chunk) {
            LiteralValue lit = e.getLiteralObject();
            Map<String, Object> row = new HashMap<>(7);
            row.put("sid", e.getSourceId());
            row.put("val", lit.getLex());
            row.put("dt", Rdf2pgMeta.toNeo4jOptional(lit.getDatatype()));
            row.put("lang", Rdf2pgMeta.toNeo4jOptional(lit.getLang()));
            row.put("dir", Rdf2pgMeta.toNeo4jOptional(lit.getDirection()));
            row.put("props", relPropsMap(e));
            rows.add(row);
        }
        tx.run(
                "UNWIND $rows AS row "
                        + "MATCH (a:" + NODE_LABEL + " {id: row.sid}) "
                        + "MERGE (lit:" + LITERAL_LABEL + " {value: row.val, datatype: row.dt, lang: row.lang, direction: row.dir}) "
                        + "CREATE (a)-[r:" + REL_TYPE + "]->(lit) SET r += row.props",
                Map.of("rows", rows));
    }

    private static Map<String, Object> relPropsMap(PropertyGraphEdge e) {
        Map<String, Object> relProps = new HashMap<>();
        relProps.put("predicate", e.getLabel());
        if (e.getReifierId() != null) {
            relProps.put(metaKeyShort(Rdf2pgMeta.REIFIER), e.getReifierId());
        }
        relProps.put(metaKeyShort(Rdf2pgMeta.ASSERTED), e.isAsserted());
        relProps.put(metaKeyShort(Rdf2pgMeta.REIFIED), e.isReified());

        Map<String, Object> annotations = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : e.annotationProperties().entrySet()) {
            relProps.put(sanitizePropKey(entry.getKey()), toNeo4jValue(entry.getValue()));
            annotations.put(entry.getKey(), entry.getValue());
        }
        if (!annotations.isEmpty()) {
            relProps.put(AnnotationJsonCodec.ANNOTATIONS_PROP, AnnotationJsonCodec.encode(annotations));
        }
        return relProps;
    }

    private static String metaKeyShort(String metaUri) {
        return metaUri.substring(Rdf2pgMeta.NS.length());
    }

    private void report(String msg) {
        try {
            progressCallback.accept(msg);
        } catch (Exception ignored) { }
    }

    private static String sanitizePropKey(String key) {
        if (key == null) return "key";
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return key.replace("://", "___").replaceAll("[^a-zA-Z0-9_]", "_");
        }
        return key.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static Object toNeo4jValue(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) continue;
                if (item instanceof String || item instanceof Number || item instanceof Boolean) {
                    out.add(item);
                } else {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        return Values.value(v.toString());
    }

    @Override
    public void close() {
        driver.close();
    }
}
