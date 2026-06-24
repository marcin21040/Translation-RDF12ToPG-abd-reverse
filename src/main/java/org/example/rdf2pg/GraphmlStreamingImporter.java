package org.example.rdf2pg;

import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.TransactionContext;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streams a GraphML file into Neo4j using the project's Resource/REL model.
 */
public class GraphmlStreamingImporter implements AutoCloseable {

    private static final int NODE_BATCH = 50_000;
    private static final int EDGE_BATCH = 50_000;

    private final Driver driver;
    private final Consumer<String> progress;

    public GraphmlStreamingImporter(Driver driver, Consumer<String> progress) {
        this.driver = driver;
        this.progress = progress != null ? progress : msg -> {};
    }

    public static GraphmlStreamingImporter create(String uri, String user, String password, Consumer<String> progress) {
        return new GraphmlStreamingImporter(
                GraphDatabase.driver(uri, org.neo4j.driver.AuthTokens.basic(user, password)),
                progress);
    }

    public ImportStats importFile(Path graphmlPath) throws Exception {
        progress.accept("Creating Neo4j indexes (non-blocking)...");
        try (var session = driver.session()) {
            Neo4jIndexHelper.ensureResourceIndexes(session);
        }

        ImportStats stats = new ImportStats();
        List<Map<String, Object>> nodeBatch = new ArrayList<>(NODE_BATCH);
        List<Map<String, Object>> edgeBatch = new ArrayList<>(EDGE_BATCH);

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);

        try (InputStream in = Files.newInputStream(graphmlPath);
             var session = driver.session()) {

            XMLStreamReader reader = factory.createXMLStreamReader(in);
            Map<String, String> currentData = new HashMap<>();
            String nodeId = null;
            String nodeLabelsAttr = null;
            String edgeSource = null;
            String edgeTarget = null;
            String edgeLabel = null;
            String dataKey = null;

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String local = reader.getLocalName();
                        if ("node".equals(local)) {
                            nodeId = reader.getAttributeValue(null, "id");
                            nodeLabelsAttr = reader.getAttributeValue(null, "labels");
                            currentData.clear();
                        } else if ("edge".equals(local)) {
                            edgeSource = reader.getAttributeValue(null, "source");
                            edgeTarget = reader.getAttributeValue(null, "target");
                            edgeLabel = reader.getAttributeValue(null, "label");
                            if (edgeLabel == null) {
                                edgeLabel = "RELATED";
                            }
                        } else if ("data".equals(local)) {
                            dataKey = reader.getAttributeValue(null, "key");
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (dataKey != null && nodeId != null && edgeSource == null) {
                            currentData.put(dataKey, reader.getText().trim());
                        } else if (dataKey != null && edgeSource != null && "label".equals(dataKey)) {
                            edgeLabel = reader.getText().trim();
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        String local = reader.getLocalName();
                        if ("data".equals(local)) {
                            dataKey = null;
                        } else if ("node".equals(local)) {
                            nodeBatch.add(buildNodeRow(nodeId, nodeLabelsAttr, currentData));
                            stats.nodes++;
                            if (nodeBatch.size() >= NODE_BATCH) {
                                flushNodes(session, nodeBatch);
                                progress.accept("Nodes imported: " + stats.nodes);
                                nodeBatch.clear();
                            }
                            nodeId = null;
                        } else if ("edge".equals(local)) {
                            edgeBatch.add(buildEdgeRow(edgeSource, edgeTarget, edgeLabel));
                            stats.edges++;
                            if (edgeBatch.size() >= EDGE_BATCH) {
                                flushEdges(session, edgeBatch);
                                progress.accept("Edges imported: " + stats.edges);
                                edgeBatch.clear();
                            }
                            edgeSource = null;
                        }
                    }
                    default -> { }
                }
            }
            reader.close();

            if (!nodeBatch.isEmpty()) {
                flushNodes(session, nodeBatch);
                nodeBatch.clear();
            }
            if (!edgeBatch.isEmpty()) {
                flushEdges(session, edgeBatch);
                edgeBatch.clear();
            }
        }

        progress.accept("Import done: " + stats.nodes + " nodes, " + stats.edges + " edges");
        return stats;
    }

    private static Map<String, Object> buildNodeRow(String graphmlId, String labelsAttr, Map<String, String> data) {
        Map<String, Object> props = new HashMap<>();
        for (Map.Entry<String, String> e : data.entrySet()) {
            if ("id".equals(e.getKey())) {
                continue;
            }
            props.put(sanitizeKey(e.getKey()), parseValue(e.getValue()));
        }
        if (labelsAttr != null && !labelsAttr.isBlank()) {
            List<String> labelUris = new ArrayList<>();
            for (String part : labelsAttr.split(":")) {
                if (!part.isBlank()) {
                    labelUris.add(PgUris.labelUri(part));
                }
            }
            if (!labelUris.isEmpty()) {
                String labelsKey = Rdf2pgMeta.NODE_LABELS.substring(Rdf2pgMeta.NS.length());
                props.put(labelsKey, labelUris);
                props.putIfAbsent("labels", labelsAttr);
            }
        }
        Map<String, Object> row = new HashMap<>(2);
        row.put("id", PgUris.nodeUri(graphmlId));
        row.put("props", props);
        return row;
    }

    private static Map<String, Object> buildEdgeRow(String source, String target, String label) {
        Map<String, Object> row = new HashMap<>(4);
        row.put("sid", PgUris.nodeUri(source));
        row.put("tid", PgUris.nodeUri(target));
        row.put("predicate", PgUris.encodePredicateUri(label));
        return row;
    }

    private static Object parseValue(String raw) {
        if (raw == null) return null;
        if (raw.matches("-?\\d+")) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) { }
        }
        if (raw.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException ignored) { }
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        return raw;
    }

    private static void flushNodes(org.neo4j.driver.Session session, List<Map<String, Object>> batch) {
        session.executeWrite(tx -> {
            mergeNodes(tx, batch);
            return null;
        });
    }

    private static void flushEdges(org.neo4j.driver.Session session, List<Map<String, Object>> batch) {
        session.executeWrite(tx -> {
            createEdges(tx, batch);
            return null;
        });
    }

    private static void mergeNodes(TransactionContext tx, List<Map<String, Object>> rows) {
        tx.run(
                "UNWIND $rows AS row "
                        + "MERGE (n:Resource {id: row.id}) "
                        + "SET n += row.props",
                Map.of("rows", rows));
    }

    private static void createEdges(TransactionContext tx, List<Map<String, Object>> rows) {
        tx.run(
                "UNWIND $rows AS row "
                        + "MATCH (a:Resource {id: row.sid}), (b:Resource {id: row.tid}) "
                        + "CREATE (a)-[:REL {predicate: row.predicate}]->(b)",
                Map.of("rows", rows));
    }

    private static String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @Override
    public void close() {
        driver.close();
    }

    public static final class ImportStats {
        public long nodes;
        public long edges;
    }
}
