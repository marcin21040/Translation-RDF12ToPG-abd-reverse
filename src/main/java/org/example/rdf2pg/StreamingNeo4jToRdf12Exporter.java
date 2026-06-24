package org.example.rdf2pg;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Streams Neo4j property graph data to RDF 1.2 N-Triples without loading all data into memory.
 */
public class StreamingNeo4jToRdf12Exporter implements AutoCloseable {

    private static final int BATCH = 50_000;
    private static final String NODE_LABEL = "Resource";
    private static final String LITERAL_LABEL = "Literal";
    private static final String REL_TYPE = "REL";
    private static final String LABELS_KEY = Rdf2pgMeta.NODE_LABELS.substring(Rdf2pgMeta.NS.length());

    private final Driver driver;
    private final Consumer<String> progress;

    public StreamingNeo4jToRdf12Exporter(Driver driver, Consumer<String> progress) {
        this.driver = driver;
        this.progress = progress != null ? progress : msg -> {};
    }

    public static StreamingNeo4jToRdf12Exporter create(String uri, String user, String password, Consumer<String> progress) {
        return new StreamingNeo4jToRdf12Exporter(
                Neo4jDrivers.create(uri, user, password),
                progress);
    }

    public ExportStats exportTo(Path outPath) throws Exception {
        ExportStats stats = new ExportStats();
        AtomicLong reifierFallback = new AtomicLong(0);

        if (Files.exists(outPath) && !Files.isWritable(outPath)) {
            throw new java.io.IOException(
                    "Cannot overwrite " + outPath.toAbsolutePath()
                            + " (not writable — run: sudo chown -R $USER results/ or sudo rm " + outPath + ")");
        }
        if (Files.exists(outPath)) {
            Files.delete(outPath);
        }

        try (BufferedWriter w = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            w.write("VERSION \"1.2\"");
            w.newLine();

            try (var session = driver.session()) {
                String lastNodeId = "";
                while (true) {
                    List<Record> batch = session.run(
                            "MATCH (n:" + NODE_LABEL + ") "
                                    + "WHERE n.id > $lastId "
                                    + "RETURN n.id AS id, properties(n) AS props "
                                    + "ORDER BY n.id "
                                    + "LIMIT $limit",
                            Map.of("lastId", lastNodeId, "limit", BATCH)
                    ).list();
                    if (batch.isEmpty()) break;

                    for (Record rec : batch) {
                        String nodeId = valueAsString(rec.get("id"));
                        if (nodeId == null || nodeId.isBlank()) {
                            continue;
                        }
                        Map<String, Object> props = rec.get("props").asMap();
                        stats.asserted += writeTypeAssertions(w, nodeId, props);
                        stats.blocks += writeNodePropertyBlocks(w, reifierFallback, nodeId, props);
                    }
                    lastNodeId = valueAsString(batch.get(batch.size() - 1).get("id"));
                    progress.accept("Node export: asserted=" + stats.asserted + " blocks=" + stats.blocks);
                }

                long lastRelId = -1L;
                while (true) {
                    List<Record> batch = session.run(
                            "MATCH (a:" + NODE_LABEL + ")-[r:" + REL_TYPE + "]->(b:" + NODE_LABEL + ") "
                                    + "WHERE id(r) > $lastRelId "
                                    + "RETURN id(r) AS rid, a.id AS sid, r AS rel, b.id AS tid "
                                    + "ORDER BY id(r) "
                                    + "LIMIT $limit",
                            Map.of("lastRelId", lastRelId, "limit", BATCH)
                    ).list();
                    if (batch.isEmpty()) break;

                    for (Record rec : batch) {
                        stats.blocks += writeResourceEdge(w, reifierFallback, rec);
                    }
                    lastRelId = recId(batch.get(batch.size() - 1));
                    progress.accept("Resource edges: " + stats.blocks + " blocks");
                }

                lastRelId = -1L;
                while (true) {
                    List<Record> batch = session.run(
                            "MATCH (a:" + NODE_LABEL + ")-[r:" + REL_TYPE + "]->(lit:" + LITERAL_LABEL + ") "
                                    + "WHERE id(r) > $lastRelId "
                                    + "RETURN id(r) AS rid, a.id AS sid, r AS rel, lit.value AS val, lit.datatype AS dt, "
                                    + "lit.lang AS lang, lit.direction AS dir "
                                    + "ORDER BY id(r) "
                                    + "LIMIT $limit",
                            Map.of("lastRelId", lastRelId, "limit", BATCH)
                    ).list();
                    if (batch.isEmpty()) break;

                    for (Record rec : batch) {
                        stats.blocks += writeLiteralEdge(w, reifierFallback, rec);
                    }
                    lastRelId = recId(batch.get(batch.size() - 1));
                    progress.accept("Literal edges: " + stats.blocks + " blocks");
                }
            }
        }

        progress.accept("Export done: " + stats.asserted + " asserted, " + stats.blocks + " reification blocks -> " + outPath);
        return stats;
    }

    private static long writeTypeAssertions(BufferedWriter w, String nodeId, Map<String, Object> props) throws Exception {
        long count = 0;
        Object labels = props.get(LABELS_KEY);
        if (labels instanceof List<?> list) {
            for (Object l : list) {
                if (l == null) continue;
                Rdf12NtWriter.writePlainTriple(w, nodeId, PgUris.RDF_TYPE, String.valueOf(l));
                count++;
            }
            return count;
        }
        Object legacy = props.get("labels");
        if (legacy != null) {
            for (String part : String.valueOf(legacy).split(":")) {
                if (part.isBlank()) continue;
                Rdf12NtWriter.writePlainTriple(w, nodeId, PgUris.RDF_TYPE, PgUris.labelUri(part));
                count++;
            }
        }
        return count;
    }

    private static long writeNodePropertyBlocks(BufferedWriter w, AtomicLong reifierFallback,
                                                String nodeId, Map<String, Object> props) throws Exception {
        long count = 0;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String key = e.getKey();
            if ("id".equals(key) || LABELS_KEY.equals(key) || "labels".equals(key)) {
                continue;
            }
            Object val = e.getValue();
            if (val == null) continue;
            ReificationBlock block = propertyBlock(nodeId, key, val);
            Rdf12NtWriter.writeBlock(w, nextReifier(null, reifierFallback), block);
            count++;
        }
        return count;
    }

    private static long writeResourceEdge(BufferedWriter w, AtomicLong reifierFallback, Record rec) throws Exception {
        String sid = valueAsString(rec.get("sid"));
        String tid = valueAsString(rec.get("tid"));
        Map<String, Object> rel = rec.get("rel").asMap();
        String pred = stringVal(rel.get("predicate"));
        if (pred == null || pred.isBlank()) {
            pred = PgUris.RDF_TYPE;
        }
        boolean asserted = boolVal(rel.get(metaKeyShort(Rdf2pgMeta.ASSERTED)));
        boolean reified = boolValOrDefault(rel.get(metaKeyShort(Rdf2pgMeta.REIFIED)), true);
        String reifier = stringVal(rel.get(metaKeyShort(Rdf2pgMeta.REIFIER)));
        Map<String, Object> annotations = Neo4jPropertyKeys.annotationProperties(rel);

        long count = 0;
        if (asserted) {
            Rdf12NtWriter.writePlainTriple(w, sid, pred, tid);
            count++;
        }
        if (reified) {
            ReificationBlock block = new ReificationBlock(
                    sid, pred, tid, null, annotations, reifier, asserted, true);
            Rdf12NtWriter.writeBlock(w, nextReifier(reifier, reifierFallback), block);
            count++;
        }
        return count;
    }

    private static long writeLiteralEdge(BufferedWriter w, AtomicLong reifierFallback, Record rec) throws Exception {
        String sid = valueAsString(rec.get("sid"));
        Map<String, Object> rel = rec.get("rel").asMap();
        String pred = stringVal(rel.get("predicate"));
        if (pred == null || pred.isBlank()) {
            pred = PgUris.RDF_TYPE;
        }
        boolean asserted = boolVal(rel.get(metaKeyShort(Rdf2pgMeta.ASSERTED)));
        boolean reified = boolValOrDefault(rel.get(metaKeyShort(Rdf2pgMeta.REIFIED)), true);
        String reifier = stringVal(rel.get(metaKeyShort(Rdf2pgMeta.REIFIER)));
        Map<String, Object> annotations = Neo4jPropertyKeys.annotationProperties(rel);
        LiteralValue lit = new LiteralValue(
                valueAsString(rec.get("val")) != null ? valueAsString(rec.get("val")) : "",
                Rdf2pgMeta.fromNeo4jOptional(valueAsString(rec.get("dt"))),
                Rdf2pgMeta.fromNeo4jOptional(valueAsString(rec.get("lang"))),
                Rdf2pgMeta.fromNeo4jOptional(valueAsString(rec.get("dir"))));

        long count = 0;
        if (asserted) {
            w.write(RdfResources.writeSubjectOrObject(sid));
            w.write(" <");
            w.write(pred);
            w.write("> ");
            w.write(Rdf12NtWriter.formatLiteralTerm(lit));
            w.write(" .");
            w.newLine();
            count++;
        }
        if (reified) {
            ReificationBlock block = new ReificationBlock(
                    sid, pred, null, lit, annotations, reifier, asserted, true);
            Rdf12NtWriter.writeBlock(w, nextReifier(reifier, reifierFallback), block);
            count++;
        }
        return count;
    }

    private static String metaKeyShort(String metaUri) {
        return metaUri.substring(Rdf2pgMeta.NS.length());
    }

    private static long recId(Record rec) {
        return rec.get("rid").asLong();
    }

    private static String nextReifier(String reifier, AtomicLong fallback) {
        if (reifier != null && !reifier.isBlank()) {
            return reifier;
        }
        return "_:rt" + fallback.getAndIncrement();
    }

    private static ReificationBlock propertyBlock(String nodeId, String key, Object val) {
        if (val instanceof Number n) {
            return new ReificationBlock(
                    nodeId, PgUris.propertyUri(key), null,
                    new LiteralValue(String.valueOf(n), numberDatatype(val), null, null),
                    Map.of(), null, false, true);
        }
        if (val instanceof Boolean b) {
            return new ReificationBlock(
                    nodeId, PgUris.propertyUri(key), null,
                    new LiteralValue(String.valueOf(b), PgUris.XSD_BOOLEAN, null, null),
                    Map.of(), null, false, true);
        }
        String s = String.valueOf(val);
        String dt = s.contains("T") && s.endsWith("Z") ? PgUris.XSD_DATE_TIME : PgUris.XSD_STRING;
        return new ReificationBlock(
                nodeId, PgUris.propertyUri(key), null,
                new LiteralValue(s, dt, null, null),
                Map.of(), null, false, true);
    }

    private static String numberDatatype(Object val) {
        if (val instanceof Double || val instanceof Float) {
            return PgUris.XSD_DOUBLE;
        }
        return PgUris.XSD_LONG;
    }

    private static String stringVal(Object o) {
        if (o == null) return null;
        if (o instanceof Value v) {
            return valueAsString(v);
        }
        return o.toString();
    }

    private static String valueAsString(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return v.asString();
        } catch (org.neo4j.driver.exceptions.value.Uncoercible e) {
            return String.valueOf(v.asObject());
        }
    }

    private static boolean boolVal(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static boolean boolValOrDefault(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    @Override
    public void close() {
        driver.close();
    }

    public static final class ExportStats {
        public long asserted;
        public long blocks;
    }
}
