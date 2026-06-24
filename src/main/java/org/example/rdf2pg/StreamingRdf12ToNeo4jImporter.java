package org.example.rdf2pg;

import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.TransactionContext;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams RDF 1.2 N-Triples (reification blocks) into Neo4j without loading the full file.
 */
public class StreamingRdf12ToNeo4jImporter implements AutoCloseable {

    private static final String REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies";
    private static final Pattern REIFIES_LINE = Pattern.compile(
            "^(_:\\S+)\\s+<" + Pattern.quote(REIFIES) + ">\\s*<<\\(\\s*<([^>]+)>\\s*<([^>]+)>\\s+(.+?)\\s*\\)>>\\s*\\.$");
    private static final Pattern PLAIN_TRIPLE = Pattern.compile(
            "^(<[^>]+>|_:\\S+)\\s+<([^>]+)>\\s+(.+?)\\s*\\.$");
    private static final int FLUSH_BLOCKS = 50_000;

    private final Driver driver;
    private final Consumer<String> progress;

    public StreamingRdf12ToNeo4jImporter(Driver driver, Consumer<String> progress) {
        this.driver = driver;
        this.progress = progress != null ? progress : msg -> {};
    }

    public static StreamingRdf12ToNeo4jImporter create(String uri, String user, String password, Consumer<String> progress) {
        return new StreamingRdf12ToNeo4jImporter(
                GraphDatabase.driver(uri, org.neo4j.driver.AuthTokens.basic(user, password)),
                progress);
    }

    public ImportStats importFile(Path ntPath) throws Exception {
        progress.accept("Creating Neo4j indexes (non-blocking)...");
        try (var session = driver.session()) {
            Neo4jIndexHelper.ensureResourceIndexes(session);
        }

        ImportStats stats = new ImportStats();
        Map<String, Map<String, Object>> nodeProps = new LinkedHashMap<>();
        Map<String, List<String>> nodeTypeLabels = new LinkedHashMap<>();
        List<Map<String, Object>> resourceEdges = new ArrayList<>();
        List<Map<String, Object>> literalEdges = new ArrayList<>();
        long blockCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(ntPath, StandardCharsets.UTF_8);
             var session = driver.session()) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("VERSION") || line.startsWith("#")) {
                    continue;
                }
                Matcher m = REIFIES_LINE.matcher(line);
                if (m.matches()) {
                    handleReifiesLine(m, nodeProps, nodeTypeLabels, resourceEdges, literalEdges, stats);
                    blockCount++;
                } else {
                    Matcher plain = PLAIN_TRIPLE.matcher(line);
                    if (plain.matches() && handlePlainTriple(plain, nodeTypeLabels, stats)) {
                        blockCount++;
                    }
                }

                if (blockCount > 0 && blockCount % FLUSH_BLOCKS == 0) {
                    mergeTypeLabels(nodeProps, nodeTypeLabels);
                    flush(session, nodeProps, resourceEdges, literalEdges, stats);
                    nodeProps.clear();
                    nodeTypeLabels.clear();
                    resourceEdges.clear();
                    literalEdges.clear();
                    blockCount = 0;
                    progress.accept("Imported blocks: " + stats.blocks);
                }
            }

            mergeTypeLabels(nodeProps, nodeTypeLabels);
            if (!nodeProps.isEmpty() || !nodeTypeLabels.isEmpty()
                    || !resourceEdges.isEmpty() || !literalEdges.isEmpty()) {
                flush(session, nodeProps, resourceEdges, literalEdges, stats);
            }
        }

        progress.accept("Import done: " + stats.blocks + " blocks, " + stats.assertedTriples
                + " asserted, " + stats.edges + " resource edges, "
                + stats.literalEdges + " literal edges, " + stats.nodeProps + " node properties");
        return stats;
    }

    private void handleReifiesLine(Matcher m,
                                   Map<String, Map<String, Object>> nodeProps,
                                   Map<String, List<String>> nodeTypeLabels,
                                   List<Map<String, Object>> resourceEdges,
                                   List<Map<String, Object>> literalEdges,
                                   ImportStats stats) {
        String reifierId = m.group(1);
        String subject = m.group(2);
        String predicate = m.group(3);
        String objectRaw = m.group(4).trim();
        stats.blocks++;

        if (PgUris.RDF_TYPE.equals(predicate)) {
            if (objectRaw.startsWith("<") && objectRaw.endsWith(">")) {
                String labelUri = objectRaw.substring(1, objectRaw.length() - 1);
                nodeTypeLabels.computeIfAbsent(subject, k -> new ArrayList<>()).add(labelUri);
            }
        } else if (objectRaw.startsWith("<") && objectRaw.endsWith(">")) {
            String target = objectRaw.substring(1, objectRaw.length() - 1);
            if (predicate.startsWith(PgUris.REL)) {
                resourceEdges.add(edgeRow(subject, target, predicate, reifierId));
                stats.edges++;
            }
        } else if (objectRaw.startsWith("_:")) {
            resourceEdges.add(edgeRow(subject, objectRaw, predicate, reifierId));
            stats.edges++;
        } else {
            literalEdges.add(literalEdgeRow(subject, predicate, LiteralTermParser.parse(objectRaw), reifierId));
            stats.literalEdges++;
        }
    }

    private static boolean handlePlainTriple(Matcher plain,
                                             Map<String, List<String>> nodeTypeLabels,
                                             ImportStats stats) {
        String subject = plain.group(1);
        if (subject.startsWith("<") && subject.endsWith(">")) {
            subject = subject.substring(1, subject.length() - 1);
        }
        String predicate = plain.group(2);
        String objectRaw = plain.group(3).trim();

        if (!PgUris.RDF_TYPE.equals(predicate)) {
            return false;
        }
        if (!objectRaw.startsWith("<") || !objectRaw.endsWith(">")) {
            return false;
        }
        String labelUri = objectRaw.substring(1, objectRaw.length() - 1);
        nodeTypeLabels.computeIfAbsent(subject, k -> new ArrayList<>()).add(labelUri);
        stats.assertedTriples++;
        return true;
    }

    private static Map<String, Object> edgeRow(String sid, String tid, String predicate, String reifierId) {
        Map<String, Object> row = new HashMap<>(5);
        row.put("sid", sid);
        row.put("tid", tid);
        row.put("predicate", predicate);
        row.put("reifier", reifierId);
        row.put("reified", true);
        row.put("asserted", false);
        return row;
    }

    private static Map<String, Object> literalEdgeRow(String sid, String predicate, LiteralValue lit, String reifierId) {
        Map<String, Object> row = new HashMap<>(8);
        row.put("sid", sid);
        row.put("predicate", predicate);
        row.put("val", lit.getLex());
        row.put("dt", Rdf2pgMeta.toNeo4jOptional(lit.getDatatype()));
        row.put("lang", Rdf2pgMeta.toNeo4jOptional(lit.getLang()));
        row.put("dir", Rdf2pgMeta.toNeo4jOptional(lit.getDirection()));
        row.put("reifier", reifierId);
        row.put("reified", true);
        row.put("asserted", false);
        return row;
    }

    private static void mergeTypeLabels(Map<String, Map<String, Object>> nodeProps,
                                        Map<String, List<String>> nodeTypeLabels) {
        String labelsKey = Rdf2pgMeta.NODE_LABELS.substring(Rdf2pgMeta.NS.length());
        for (Map.Entry<String, List<String>> e : nodeTypeLabels.entrySet()) {
            Map<String, Object> props = nodeProps.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
            props.put(labelsKey, mergeLabelUris(props.get(labelsKey), e.getValue()));
        }
    }

    static List<String> mergeLabelUris(Object existing, List<String> added) {
        List<String> merged = new ArrayList<>();
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String uri = String.valueOf(item);
                    if (!merged.contains(uri)) {
                        merged.add(uri);
                    }
                }
            }
        }
        if (added != null) {
            for (String uri : added) {
                if (uri != null && !merged.contains(uri)) {
                    merged.add(uri);
                }
            }
        }
        return merged;
    }

    private static void flush(org.neo4j.driver.Session session,
                              Map<String, Map<String, Object>> nodeProps,
                              List<Map<String, Object>> resourceEdges,
                              List<Map<String, Object>> literalEdges,
                              ImportStats stats) {
        String labelsKey = Rdf2pgMeta.NODE_LABELS.substring(Rdf2pgMeta.NS.length());
        session.executeWrite(tx -> {
            if (!nodeProps.isEmpty()) {
                List<Map<String, Object>> rows = new ArrayList<>(nodeProps.size());
                for (Map.Entry<String, Map<String, Object>> e : nodeProps.entrySet()) {
                    Map<String, Object> props = new LinkedHashMap<>(e.getValue());
                    Object nodeLabels = props.remove(labelsKey);
                    Map<String, Object> row = new HashMap<>(3);
                    row.put("id", e.getKey());
                    row.put("props", props);
                    row.put("nodeLabels", nodeLabels);
                    rows.add(row);
                }
                tx.run(
                        "UNWIND $rows AS row "
                                + "MERGE (n:Resource {id: row.id}) "
                                + "SET n += row.props "
                                + "WITH n, row "
                                + "WHERE row.nodeLabels IS NOT NULL "
                                + "SET n." + labelsKey + " = coalesce(n." + labelsKey + ", []) "
                                + "+ [x IN row.nodeLabels WHERE NOT x IN coalesce(n." + labelsKey + ", []) | x]",
                        Map.of("rows", rows));
                stats.nodesMerged += rows.size();
            }
            if (!resourceEdges.isEmpty()) {
                createResourceEdges(tx, resourceEdges);
            }
            if (!literalEdges.isEmpty()) {
                createLiteralEdges(tx, literalEdges);
            }
            return null;
        });
    }

    private static void createResourceEdges(TransactionContext tx, List<Map<String, Object>> rows) {
        tx.run(
                "UNWIND $rows AS row "
                        + "MERGE (a:Resource {id: row.sid}) "
                        + "MERGE (b:Resource {id: row.tid}) "
                        + "CREATE (a)-[:REL {predicate: row.predicate, reifier: row.reifier, "
                        + "reified: row.reified, asserted: row.asserted}]->(b)",
                Map.of("rows", rows));
    }

    private static void createLiteralEdges(TransactionContext tx, List<Map<String, Object>> rows) {
        tx.run(
                "UNWIND $rows AS row "
                        + "MERGE (a:Resource {id: row.sid}) "
                        + "MERGE (lit:Literal {value: row.val, datatype: row.dt, lang: row.lang, direction: row.dir}) "
                        + "CREATE (a)-[:REL {predicate: row.predicate, reifier: row.reifier, "
                        + "reified: row.reified, asserted: row.asserted}]->(lit)",
                Map.of("rows", rows));
    }

    @Override
    public void close() {
        driver.close();
    }

    public static final class ImportStats {
        public long blocks;
        public long assertedTriples;
        public long edges;
        public long literalEdges;
        public long nodeProps;
        public long nodesMerged;
    }
}
