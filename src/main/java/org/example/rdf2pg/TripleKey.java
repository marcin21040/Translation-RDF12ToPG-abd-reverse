package org.example.rdf2pg;

import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

import java.util.Objects;

/** Canonical identity of an RDF triple (S, P, O) for deduplication. */
public final class TripleKey implements Comparable<TripleKey> {

    private final String subject;
    private final String predicate;
    private final String objectKey;

    public TripleKey(String subject, String predicate, String objectKey) {
        this.subject = Objects.requireNonNull(subject);
        this.predicate = Objects.requireNonNull(predicate);
        this.objectKey = Objects.requireNonNull(objectKey);
    }

    public static TripleKey from(Resource subject, String predicateUri, RDFNode object) {
        return new TripleKey(
                RdfResources.resourceId(subject),
                predicateUri,
                objectKey(object));
    }

    public static TripleKey fromBlock(ReificationBlock block) {
        if (block.isObjectLiteral()) {
            LiteralValue lit = block.getObjectLiteral();
            return new TripleKey(
                    block.getSubjectUri(),
                    block.getPredicateUri(),
                    "L:" + lit.canonicalKey());
        }
        return new TripleKey(block.getSubjectUri(), block.getPredicateUri(), "U:" + block.getObjectUri());
    }

    private static String objectKey(RDFNode object) {
        if (object.isResource()) {
            return "U:" + RdfResources.resourceId(object.asResource());
        }
        return "L:" + LiteralValue.fromJena(object.asLiteral()).canonicalKey();
    }

    public String getSubject() {
        return subject;
    }

    public String getPredicate() {
        return predicate;
    }

    public String getObjectKey() {
        return objectKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TripleKey that)) return false;
        return subject.equals(that.subject)
                && predicate.equals(that.predicate)
                && objectKey.equals(that.objectKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, predicate, objectKey);
    }

    @Override
    public int compareTo(TripleKey o) {
        int c = subject.compareTo(o.subject);
        if (c != 0) return c;
        c = predicate.compareTo(o.predicate);
        if (c != 0) return c;
        return objectKey.compareTo(o.objectKey);
    }
}
