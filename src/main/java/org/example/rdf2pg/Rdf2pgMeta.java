package org.example.rdf2pg;

import java.util.Set;

/** Reserved technical properties — not RDF annotations. */
public final class Rdf2pgMeta {

    public static final String NS = "http://rdf2pg.example/meta#";
    public static final String REIFIER = NS + "reifier";
    public static final String ASSERTED = NS + "asserted";
    public static final String REIFIED = NS + "reified";
    public static final String VALUE = NS + "value";
    public static final String DATATYPE = NS + "datatype";
    public static final String LANG = NS + "lang";
    public static final String DIRECTION = NS + "direction";
    public static final String NODE_LABELS = NS + "nodeLabels";

    /** Legacy keys kept for Neo4j literal edges during migration. */
    public static final String LEGACY_VALUE = "value";
    public static final String LEGACY_DATATYPE = "datatype";

    private static final Set<String> RESERVED = Set.of(
            REIFIER, ASSERTED, REIFIED, VALUE, DATATYPE, LANG, DIRECTION,
            LEGACY_VALUE, LEGACY_DATATYPE,
            "predicate", AnnotationJsonCodec.ANNOTATIONS_PROP);

    private Rdf2pgMeta() {}

    public static boolean isReservedKey(String key) {
        return key != null && RESERVED.contains(key);
    }

    public static boolean isAnnotationKey(String key) {
        return key != null && !isReservedKey(key) && !NODE_LABELS.equals(key);
    }

    /** Neo4j cannot store null in MERGE keys — use empty string. */
    public static String toNeo4jOptional(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /** Restore null for optional literal fields read from Neo4j. */
    public static String fromNeo4jOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
