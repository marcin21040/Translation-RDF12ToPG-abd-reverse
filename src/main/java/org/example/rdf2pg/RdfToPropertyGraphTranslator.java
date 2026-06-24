package org.example.rdf2pg;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

/**
 * Translacja RDF 1.2 na graf własności z polityką dualną:
 * reifikacja nie implikuje asercji; jawna trójka tworzy/uzupełnia krawędź; deduplikacja (S,P,O).
 */
public class RdfToPropertyGraphTranslator {

    private static final String RDF_REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies";

    private final PropertyGraph graph = new PropertyGraph();

    public PropertyGraph translate(Model model) {
        return translate(model, Map.of());
    }

    public PropertyGraph translate(Model model, Map<TripleKey, String> reifierIdsByTriple) {
        Rdf12InputProfileValidator.validate(model);

        Map<Resource, List<Statement>> bySubject = new HashMap<>();
        Set<Resource> reifiers = new HashSet<>();
        List<Statement> allStatements = new ArrayList<>();

        StmtIterator all = model.listStatements();
        while (all.hasNext()) {
            Statement st = all.next();
            allStatements.add(st);
            bySubject.computeIfAbsent(st.getSubject(), k -> new ArrayList<>()).add(st);
            if (RDF_REIFIES.equals(st.getPredicate().getURI()) && st.getObject().isStatementTerm()) {
                reifiers.add(st.getSubject());
            }
        }
        all.close();

        Map<TripleKey, PropertyGraphEdge> edgesByTriple = new LinkedHashMap<>();

        for (Resource reifier : reifiers) {
            String reifierId = RdfResources.resourceId(reifier);
            Map<String, Object> edgeProps = new LinkedHashMap<>();
            List<Statement> reified = new ArrayList<>();

            for (Statement st : bySubject.getOrDefault(reifier, List.of())) {
                String predUri = st.getPredicate().getURI();
                if (RDF_REIFIES.equals(predUri)) {
                    if (st.getObject().isStatementTerm()) {
                        reified.add(st.getObject().asStatementTerm().getStatement());
                    }
                } else {
                    Object value = nodeToPropertyValue(st.getObject());
                    if (value != null) {
                        putMulti(edgeProps, predUri, value);
                    }
                }
            }

            for (Statement inner : reified) {
                TripleKey key = TripleKey.from(
                        inner.getSubject(), inner.getPredicate().getURI(), inner.getObject());
                PropertyGraphEdge edge = edgesByTriple.get(key);
                if (edge == null) {
                    PropertyGraphEdge created = createEdgeFromTriple(
                            inner.getSubject(), inner.getPredicate().getURI(), inner.getObject());
                    created.setReified(true);
                    created.setReifierId(resolveReifierId(key, reifierId, reifierIdsByTriple));
                    edgeProps.forEach(created::setProperty);
                    edgesByTriple.put(key, created);
                    graph.addEdge(created);
                } else {
                    edge.setReified(true);
                    if (edge.getReifierId() == null) {
                        edge.setReifierId(resolveReifierId(key, reifierId, reifierIdsByTriple));
                    }
                    PropertyGraphEdge target = edge;
                    edgeProps.forEach((k, v) -> mergeAnnotation(target, k, v));
                }
            }
        }

        for (Statement st : allStatements) {
            Resource s = st.getSubject();
            if (reifiers.contains(s)) {
                continue;
            }
            String pUri = st.getPredicate().getURI();
            if (RDF_REIFIES.equals(pUri)) {
                continue;
            }
            if (RDF.type.getURI().equals(pUri) && st.getObject().isResource()) {
                graph.getOrCreateNode(RdfResources.resourceId(s))
                        .addLabel(st.getObject().asResource().getURI());
                continue;
            }

            TripleKey key = TripleKey.from(s, pUri, st.getObject());
            PropertyGraphEdge existing = edgesByTriple.get(key);
            if (existing != null) {
                existing.setAsserted(true);
            } else {
                PropertyGraphEdge edge = createEdgeFromTriple(s, pUri, st.getObject());
                edge.setAsserted(true);
                edge.setReified(false);
                edgesByTriple.put(key, edge);
                graph.addEdge(edge);
            }
        }

        return graph;
    }

    private PropertyGraphEdge createEdgeFromTriple(Resource subject, String predicateUri, RDFNode object) {
        String sourceId = RdfResources.resourceId(subject);
        graph.getOrCreateNode(sourceId);

        String targetId = null;
        if (object.isResource()) {
            targetId = RdfResources.resourceId(object.asResource());
            graph.getOrCreateNode(targetId);
        }

        PropertyGraphEdge edge = new PropertyGraphEdge(sourceId, targetId, predicateUri);
        if (object.isLiteral()) {
            edge.setLiteralObject(LiteralValue.fromJena(object.asLiteral()));
        }
        return edge;
    }

    private static String resolveReifierId(TripleKey key, String fromModel, Map<TripleKey, String> fromNt) {
        String fromFile = fromNt.get(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        return fromModel;
    }

    private static void putMulti(Map<String, Object> props, String key, Object value) {
        Object existing = props.get(key);
        if (existing == null) {
            props.put(key, value);
            return;
        }
        if (existing instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) list;
            l.add(value);
            return;
        }
        List<Object> l = new ArrayList<>();
        l.add(existing);
        l.add(value);
        props.put(key, l);
    }

    private static void mergeAnnotation(PropertyGraphEdge edge, String key, Object value) {
        Object existing = edge.getProperties().get(key);
        if (existing == null) {
            edge.setProperty(key, value);
            return;
        }
        if (existing instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) list;
            if (value instanceof List<?> vl) {
                l.addAll(vl);
            } else {
                l.add(value);
            }
            return;
        }
        List<Object> l = new ArrayList<>();
        l.add(existing);
        if (value instanceof List<?> vl) {
            l.addAll(vl);
        } else {
            l.add(value);
        }
        edge.setProperty(key, l);
    }

    private static Object nodeToPropertyValue(RDFNode n) {
        if (n == null) return null;
        if (n.isLiteral()) {
            LiteralValue lit = LiteralValue.fromJena(n.asLiteral());
            return lit.getLex();
        }
        if (n.isResource()) return RdfResources.resourceId(n.asResource());
        return null;
    }

    public PropertyGraph getGraph() {
        return graph;
    }
}
