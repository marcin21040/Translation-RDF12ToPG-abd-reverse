package org.example.rdf2pg;

import org.apache.jena.rdf.model.Resource;

/** Resource / blank-node id formatting shared across translators. */
public final class RdfResources {

    private RdfResources() {}

    public static String resourceId(Resource r) {
        if (r == null) return "";
        if (r.isAnon()) {
            return "_:" + r.getId().getLabelString();
        }
        return r.getURI();
    }

    public static String formatReifier(String reifierId) {
        if (reifierId == null || reifierId.isBlank()) {
            return null;
        }
        if (reifierId.startsWith("_:")) {
            return reifierId;
        }
        if (reifierId.startsWith("<") && reifierId.endsWith(">")) {
            return reifierId.substring(1, reifierId.length() - 1);
        }
        return reifierId.startsWith("http://") || reifierId.startsWith("https://")
                ? "<" + reifierId + ">"
                : reifierId;
    }

    public static String writeSubjectOrObject(String id) {
        if (id == null) return "";
        if (id.startsWith("_:")) {
            return id;
        }
        return "<" + id + ">";
    }
}
