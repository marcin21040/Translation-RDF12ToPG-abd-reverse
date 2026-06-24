package org.example.rdf2pg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graf własności: zbiór węzłów i krawędzi z właściwościami.
 */
public class PropertyGraph {

    private final Map<String, PropertyGraphNode> nodes = new LinkedHashMap<>();
    private final List<PropertyGraphEdge> edges = new ArrayList<>();

    public Map<String, PropertyGraphNode> getNodes() {
        return nodes;
    }

    public List<PropertyGraphEdge> getEdges() {
        return edges;
    }

    public PropertyGraphNode getOrCreateNode(String id) {
        return nodes.computeIfAbsent(id, PropertyGraphNode::new);
    }

    public void addEdge(PropertyGraphEdge edge) {
        edges.add(edge);
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getEdgeCount() {
        return edges.size();
    }
}
