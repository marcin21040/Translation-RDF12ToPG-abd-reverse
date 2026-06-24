package org.example.rdf2pg;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates the accepted RDF 1.2 input profile before translation to LPG:
 * <ul>
 *   <li>each reifier has exactly one {@code rdf:reifies} triple term;</li>
 *   <li>each triple term has at most one reifier;</li>
 *   <li>{@code rdf:type} may not appear only inside a reified triple term.</li>
 * </ul>
 */
public final class Rdf12InputProfileValidator {

    private static final String RDF_REIFIES = RDF.reifies.getURI();

    private Rdf12InputProfileValidator() {}

    public static void validate(Model model) {
        validate(model, !isLenientProfile());
    }

    /** When lenient, a reifier may annotate more than one triple term (YAGO has ~208 such cases). */
    public static void validate(Model model, boolean strict) {
        Map<String, TripleKey> reifierToTerm = new HashMap<>();
        Map<TripleKey, String> termToReifier = new HashMap<>();

        StmtIterator it = model.listStatements(null, model.createProperty(RDF_REIFIES), (org.apache.jena.rdf.model.RDFNode) null);
        while (it.hasNext()) {
            Statement st = it.next();
            String reifierId = RdfResources.resourceId(st.getSubject());

            if (!st.getObject().isStatementTerm()) {
                throw new InputProfileViolationException(
                        "Invalid rdf:reifies object for reifier " + reifierId
                                + ": expected a triple term <<( S P O )>>, got "
                                + describeNode(st.getObject()));
            }

            Statement inner = st.getObject().asStatementTerm().getStatement();
            String predicateUri = inner.getPredicate().getURI();
            if (RDF.type.getURI().equals(predicateUri)) {
                TripleKey key = TripleKey.from(inner.getSubject(), predicateUri, inner.getObject());
                throw new InputProfileViolationException(
                        "Reified rdf:type is outside the accepted input profile: "
                                + key + " on reifier " + reifierId
                                + " (use an explicit assertion triple instead)");
            }

            TripleKey termKey = TripleKey.from(inner.getSubject(), predicateUri, inner.getObject());

            if (strict) {
                TripleKey previousTerm = reifierToTerm.put(reifierId, termKey);
                if (previousTerm != null && !previousTerm.equals(termKey)) {
                    throw new InputProfileViolationException(
                            "Reifier " + reifierId + " points to more than one triple term: "
                                    + previousTerm + " and " + termKey);
                }
            }

            String previousReifier = termToReifier.put(termKey, reifierId);
            if (previousReifier != null && !previousReifier.equals(reifierId)) {
                throw new InputProfileViolationException(
                        "Triple term " + termKey + " has more than one reifier: "
                                + previousReifier + " and " + reifierId);
            }
        }
        it.close();
    }

    private static boolean isLenientProfile() {
        String v = System.getenv("RDF12_INPUT_PROFILE_LENIENT");
        if (v == null || v.isBlank()) {
            v = System.getProperty("rdf12.input.profile.lenient", "false");
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v.trim());
    }

    private static String describeNode(org.apache.jena.rdf.model.RDFNode node) {
        if (node == null) {
            return "null";
        }
        if (node.isURIResource()) {
            return "<" + node.asResource().getURI() + ">";
        }
        if (node.isAnon()) {
            return "_:" + node.asResource().getId().getLabelString();
        }
        if (node.isLiteral()) {
            return "\"" + node.asLiteral().getLexicalForm() + "\"";
        }
        return node.toString();
    }
}
