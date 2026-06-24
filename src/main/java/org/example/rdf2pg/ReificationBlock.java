package org.example.rdf2pg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Jeden blok RDF 1.2: reifikowany fakt (S, P, O) oraz annotacje na reifikatorze.
 */
public final class ReificationBlock implements Comparable<ReificationBlock> {

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private final String subjectUri;
    private final String predicateUri;
    private final String objectUri;
    private final LiteralValue objectLiteral;
    private final Map<String, List<String>> annotations;
    private final String reifierId;
    private final boolean asserted;
    private final boolean reified;

    public ReificationBlock(
            String subjectUri,
            String predicateUri,
            String objectUri,
            String objectLiteralLex,
            String objectLiteralDatatype,
            Map<String, ?> annotations) {
        this(subjectUri, predicateUri, objectUri,
                objectUri == null ? new LiteralValue(objectLiteralLex, objectLiteralDatatype, null, null) : null,
                annotations, null, false, true);
    }

    public ReificationBlock(
            String subjectUri,
            String predicateUri,
            String objectUri,
            LiteralValue objectLiteral,
            Map<String, ?> annotations,
            String reifierId,
            boolean asserted,
            boolean reified) {
        this.subjectUri = subjectUri;
        this.predicateUri = predicateUri;
        this.objectUri = objectUri;
        this.objectLiteral = objectLiteral;
        this.reifierId = reifierId;
        this.asserted = asserted;
        this.reified = reified;
        TreeMap<String, List<String>> ann = new TreeMap<>();
        if (annotations != null) {
            for (Map.Entry<String, ?> e : annotations.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || Rdf2pgMeta.isReservedKey(e.getKey())) {
                    continue;
                }
                Object v = e.getValue();
                List<String> vals = new ArrayList<>();
                if (v instanceof List<?> list) {
                    for (Object it : list) {
                        if (it != null) vals.add(String.valueOf(it));
                    }
                } else {
                    vals.add(String.valueOf(v));
                }
                ann.put(e.getKey(), vals);
            }
        }
        this.annotations = ann;
    }

    public String getSubjectUri() {
        return subjectUri;
    }

    public String getPredicateUri() {
        return predicateUri;
    }

    public String getObjectUri() {
        return objectUri;
    }

    public String getObjectLiteralLex() {
        return objectLiteral != null ? objectLiteral.getLex() : null;
    }

    public String getObjectLiteralDatatype() {
        return objectLiteral != null ? objectLiteral.getDatatype() : null;
    }

    public LiteralValue getObjectLiteral() {
        return objectLiteral;
    }

    public Map<String, List<String>> getAnnotations() {
        return annotations;
    }

    public String getReifierId() {
        return reifierId;
    }

    public boolean isAsserted() {
        return asserted;
    }

    public boolean isReified() {
        return reified;
    }

    public boolean isObjectLiteral() {
        return objectUri == null;
    }

    public String canonicalKey() {
        StringBuilder sb = new StringBuilder();
        sb.append(subjectUri).append('\t').append(predicateUri).append('\t');
        if (objectUri != null) {
            sb.append(objectUri);
        } else if (objectLiteral != null) {
            sb.append(objectLiteral.canonicalKey());
        } else {
            sb.append("\"\"^^").append(XSD_STRING);
        }
        for (Map.Entry<String, List<String>> e : annotations.entrySet()) {
            List<String> vals = e.getValue();
            if (vals == null || vals.isEmpty()) continue;
            Set<String> uniq = new LinkedHashSet<>(vals);
            List<String> sorted = new ArrayList<>(uniq);
            sorted.sort(String::compareTo);
            for (String v : sorted) {
                sb.append('\t').append(e.getKey()).append('=').append(v);
            }
        }
        return sb.toString();
    }

    @Override
    public int compareTo(ReificationBlock o) {
        return canonicalKey().compareTo(o.canonicalKey());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReificationBlock that)) return false;
        return canonicalKey().equals(that.canonicalKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonicalKey());
    }
}
