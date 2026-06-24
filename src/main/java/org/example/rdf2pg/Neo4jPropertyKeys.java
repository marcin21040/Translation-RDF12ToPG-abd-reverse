package org.example.rdf2pg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Przywracanie URI właściwości z kluczy zsanityzowanych przez {@link PropertyGraphNeo4jWriter}.
 */
public final class Neo4jPropertyKeys {

    private static final String PREDICATE = "predicate";
    private static final String ANNOTATIONS = AnnotationJsonCodec.ANNOTATIONS_PROP;

    private Neo4jPropertyKeys() {}

    public static boolean isReservedRelKey(String key) {
        if (PREDICATE.equals(key) || ANNOTATIONS.equals(key)) {
            return true;
        }
        if ("value".equals(key) || "datatype".equals(key) || "lang".equals(key) || "direction".equals(key)) {
            return true;
        }
        String reifier = Rdf2pgMeta.REIFIER.substring(Rdf2pgMeta.NS.length());
        String asserted = Rdf2pgMeta.ASSERTED.substring(Rdf2pgMeta.NS.length());
        String reified = Rdf2pgMeta.REIFIED.substring(Rdf2pgMeta.NS.length());
        return reifier.equals(key) || asserted.equals(key) || reified.equals(key);
    }

    /** Annotation properties stored on a Neo4j REL relationship map. */
    public static Map<String, Object> annotationProperties(Map<String, Object> relMap) {
        Object annRaw = relMap.get(ANNOTATIONS);
        if (annRaw != null) {
            return AnnotationJsonCodec.decode(annRaw.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : relMap.entrySet()) {
            if (isReservedRelKey(e.getKey())) {
                continue;
            }
            out.put(unsanitize(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * Odwrócenie sanitize; dla predykatów GraphML próbuje {@link PgUris#decodePredicateUri(String)}.
     */
    public static String unsanitize(String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith("http___")) {
            if (key.startsWith("http___schema_org_")) {
                return "http://schema.org/" + key.substring("http___schema_org_".length());
            }
            return key.replace("http___", "http://").replace('_', '/');
        }
        if (key.startsWith(PgUris.REL)) {
            return PgUris.decodePredicateUri(key);
        }
        return key;
    }
}
