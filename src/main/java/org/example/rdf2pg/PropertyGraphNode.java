package org.example.rdf2pg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Węzeł grafu własności: identyfikator, opcjonalne etykiety i właściwości (key-value).
 */
public class PropertyGraphNode {

    private final String id;
    private final Set<String> labels = new TreeSet<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public PropertyGraphNode(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public Set<String> getLabels() {
        return labels;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void addLabel(String label) {
        if (label != null && !label.isEmpty()) {
            labels.add(label);
        }
    }

    public void setProperty(String key, Object value) {
        if (key != null && value != null) {
            properties.put(key, value);
        }
    }
}
