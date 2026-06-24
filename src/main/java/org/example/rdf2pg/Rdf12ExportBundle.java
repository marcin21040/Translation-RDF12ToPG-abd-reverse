package org.example.rdf2pg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** RDF 1.2 export: plain asserted triples plus reification blocks. */
public final class Rdf12ExportBundle {

    private final List<String> assertedTriples = new ArrayList<>();
    private final List<ReificationBlock> reificationBlocks = new ArrayList<>();

    public void addAssertedTriple(String subjectUri, String predicateUri, String objectUri) {
        assertedTriples.add(formatAssertedTriple(subjectUri, predicateUri, objectUri, null));
    }

    public void addAssertedLiteralTriple(String subjectUri, String predicateUri, LiteralValue literal) {
        assertedTriples.add(formatAssertedTriple(subjectUri, predicateUri, null, literal));
    }

    public void addReificationBlock(ReificationBlock block) {
        reificationBlocks.add(block);
    }

    public List<String> getAssertedTriples() {
        return Collections.unmodifiableList(assertedTriples);
    }

    public List<ReificationBlock> getReificationBlocks() {
        return Collections.unmodifiableList(reificationBlocks);
    }

    private static String formatAssertedTriple(String s, String p, String oUri, LiteralValue lit) {
        StringBuilder sb = new StringBuilder();
        sb.append(RdfResources.writeSubjectOrObject(s));
        sb.append(" <").append(p).append("> ");
        if (oUri != null) {
            sb.append(RdfResources.writeSubjectOrObject(oUri));
        } else {
            sb.append(Rdf12NtWriter.formatLiteralTerm(lit));
        }
        sb.append(" .");
        return sb.toString();
    }
}
