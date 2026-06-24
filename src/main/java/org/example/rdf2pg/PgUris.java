package org.example.rdf2pg;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * URI scheme for mapping a generic property graph (e.g. GraphML) to RDF 1.2.
 */
public final class PgUris {

    public static final String BASE = "http://pg.example/";
    public static final String NODE = BASE + "node/";
    public static final String REL = BASE + "relation/";
    public static final String PROP = BASE + "property/";
    public static final String LABEL = BASE + "label/";
    public static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    public static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";
    public static final String XSD_LONG = "http://www.w3.org/2001/XMLSchema#long";
    public static final String XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double";
    public static final String XSD_BOOLEAN = "http://www.w3.org/2001/XMLSchema#boolean";
    public static final String XSD_DATE_TIME = "http://www.w3.org/2001/XMLSchema#dateTime";

    private PgUris() {}

    public static String nodeUri(String graphmlNodeId) {
        return NODE + graphmlNodeId;
    }

    /** Lossless predicate encoding for GraphML / streaming paths. */
    public static String encodePredicateUri(String fullUri) {
        if (fullUri == null || fullUri.isBlank()) {
            return REL + "unknown";
        }
        if (fullUri.startsWith(REL)) {
            return fullUri;
        }
        return REL + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(fullUri.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodePredicateUri(String encodedUri) {
        if (encodedUri == null) {
            return null;
        }
        if (!encodedUri.startsWith(REL)) {
            return encodedUri;
        }
        String tail = encodedUri.substring(REL.length());
        if (tail.matches("^[A-Za-z0-9_-]+$") && !tail.contains("_unknown")) {
            try {
                return new String(Base64.getUrlDecoder().decode(tail), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // legacy sanitized segment
            }
        }
        return relationUri(tail);
    }

    /** @deprecated prefer {@link #encodePredicateUri(String)} for lossless round-trip */
    public static String relationUri(String label) {
        return REL + sanitize(label);
    }

    public static String propertyUri(String key) {
        return PROP + sanitize(key);
    }

    public static String labelUri(String neo4jLabel) {
        String cleaned = neo4jLabel.startsWith(":") ? neo4jLabel.substring(1) : neo4jLabel;
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return cleaned;
        }
        return LABEL + sanitize(cleaned);
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
