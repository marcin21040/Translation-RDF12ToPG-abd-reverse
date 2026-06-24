package org.example.rdf2pg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translacja grafu własności → RDF 1.2: jawne asercje + bloki reifikacji.
 */
public class PropertyGraphToRdf12Translator {

    public Rdf12ExportBundle translate(PropertyGraph graph) {
        Rdf12ExportBundle bundle = new Rdf12ExportBundle();

        for (PropertyGraphNode node : graph.getNodes().values()) {
            for (String label : node.getLabels()) {
                bundle.addAssertedTriple(node.getId(), PgUris.RDF_TYPE, label);
            }
        }

        List<ReificationBlock> blocks = new ArrayList<>(graph.getEdgeCount());
        for (PropertyGraphEdge e : graph.getEdges()) {
            if (e.isAsserted()) {
                if (e.isLiteralEdge()) {
                    bundle.addAssertedLiteralTriple(e.getSourceId(), e.getLabel(), e.getLiteralObject());
                } else {
                    bundle.addAssertedTriple(e.getSourceId(), e.getLabel(), e.getTargetId());
                }
            }
            if (e.isReified()) {
                blocks.add(edgeToBlock(e));
            }
        }
        blocks.sort(ReificationBlock::compareTo);
        blocks.forEach(bundle::addReificationBlock);
        return bundle;
    }

    /** Backward-compatible: reification blocks only (no separate asserted triples). */
    public List<ReificationBlock> translateBlocksOnly(PropertyGraph graph) {
        List<ReificationBlock> blocks = new ArrayList<>();
        translate(graph).getReificationBlocks().forEach(blocks::add);
        return blocks;
    }

    private static ReificationBlock edgeToBlock(PropertyGraphEdge e) {
        String subject = e.getSourceId();
        String predicate = e.getLabel();
        String objectUri = e.isLiteralEdge() ? null : e.getTargetId();
        LiteralValue lit = e.isLiteralEdge() ? e.getLiteralObject() : null;
        return new ReificationBlock(
                subject, predicate, objectUri, lit,
                e.annotationProperties(), e.getReifierId(), e.isAsserted(), e.isReified());
    }
}
