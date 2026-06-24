package org.example.rdf2pg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Krawędź grafu własności: źródło, cel, etykieta (typ relacji) i opcjonalne właściwości.
 * Obiekt literałowy: {@code targetId == null} + {@link LiteralValue} w meta-właściwościach.
 */
public class PropertyGraphEdge {

    private final String sourceId;
    private final String targetId;
    private final String label;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private String reifierId;
    private boolean asserted;
    private boolean reified;

    public PropertyGraphEdge(String sourceId, String targetId, String label) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.label = label;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getLabel() {
        return label;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public String getReifierId() {
        return reifierId;
    }

    public void setReifierId(String reifierId) {
        this.reifierId = reifierId;
    }

    public boolean isAsserted() {
        return asserted;
    }

    public void setAsserted(boolean asserted) {
        this.asserted = asserted;
    }

    public boolean isReified() {
        return reified;
    }

    public void setReified(boolean reified) {
        this.reified = reified;
    }

    public boolean isLiteralEdge() {
        return targetId == null;
    }

    public void setLiteralObject(LiteralValue lit) {
        if (lit == null) return;
        setProperty(Rdf2pgMeta.VALUE, lit.getLex());
        setProperty(Rdf2pgMeta.DATATYPE, lit.getDatatype());
        if (lit.hasLang()) {
            setProperty(Rdf2pgMeta.LANG, lit.getLang());
        }
        if (lit.getDirection() != null) {
            setProperty(Rdf2pgMeta.DIRECTION, lit.getDirection());
        }
        setProperty(Rdf2pgMeta.LEGACY_VALUE, lit.getLex());
        if (lit.getDatatype() != null) {
            setProperty(Rdf2pgMeta.LEGACY_DATATYPE, lit.getDatatype());
        }
    }

    public LiteralValue getLiteralObject() {
        if (!isLiteralEdge()) {
            return null;
        }
        Object v = properties.get(Rdf2pgMeta.VALUE);
        if (v == null) {
            v = properties.get(Rdf2pgMeta.LEGACY_VALUE);
        }
        Object dt = properties.get(Rdf2pgMeta.DATATYPE);
        if (dt == null) {
            dt = properties.get(Rdf2pgMeta.LEGACY_DATATYPE);
        }
        Object lang = properties.get(Rdf2pgMeta.LANG);
        Object dir = properties.get(Rdf2pgMeta.DIRECTION);
        return new LiteralValue(
                v != null ? String.valueOf(v) : "",
                dt != null ? String.valueOf(dt) : null,
                lang != null ? String.valueOf(lang) : null,
                dir != null ? String.valueOf(dir) : null);
    }

    public void setProperty(String key, Object value) {
        if (key != null && value != null) {
            properties.put(key, value);
        }
    }

    public Map<String, Object> annotationProperties() {
        Map<String, Object> ann = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            if (Rdf2pgMeta.isAnnotationKey(e.getKey())) {
                ann.put(e.getKey(), e.getValue());
            }
        }
        return ann;
    }
}
