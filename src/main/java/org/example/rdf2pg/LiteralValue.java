package org.example.rdf2pg;

import org.apache.jena.rdf.model.Literal;

import java.util.Objects;

/** RDF literal with lexical form, datatype, language tag and text direction. */
public final class LiteralValue {

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private final String lex;
    private final String datatype;
    private final String lang;
    private final String direction;

    public LiteralValue(String lex, String datatype, String lang, String direction) {
        this.lex = lex != null ? lex : "";
        this.datatype = normalizeDatatype(datatype, lang);
        this.lang = blankToNull(lang);
        this.direction = blankToNull(direction);
    }

    public static LiteralValue fromJena(Literal lit) {
        if (lit == null) {
            return new LiteralValue("", XSD_STRING, null, null);
        }
        String lang = lit.getLanguage();
        String dt = lit.getDatatypeURI();
        String dir = null;
        try {
            dir = lit.getBaseDirection();
        } catch (Exception ignored) {
            // Jena version may not expose direction on all literals
        }
        Object v;
        try {
            v = lit.getValue();
        } catch (Exception e) {
            v = lit.getLexicalForm();
        }
        String lex;
        if (v instanceof String s) {
            lex = s;
        } else if (v instanceof Number || v instanceof Boolean) {
            lex = lit.getLexicalForm();
        } else {
            lex = lit.getLexicalForm();
        }
        return new LiteralValue(lex, dt, lang, dir);
    }

    public static LiteralValue parseNtTerm(String term) {
        return LiteralTermParser.parse(term);
    }

    public String getLex() {
        return lex;
    }

    public String getDatatype() {
        return datatype;
    }

    public String getLang() {
        return lang;
    }

    public String getDirection() {
        return direction;
    }

    public boolean hasLang() {
        return lang != null && !lang.isBlank();
    }

    public String canonicalKey() {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(lex).append('"');
        if (hasLang()) {
            sb.append('@').append(lang);
            if (direction != null) {
                sb.append("--").append(direction);
            }
        }
        if (datatype != null && !datatype.isBlank() && !XSD_STRING.equals(datatype)) {
            sb.append("^^").append(datatype);
        }
        return sb.toString();
    }

    private static String normalizeDatatype(String datatype, String lang) {
        if (lang != null && !lang.isBlank()) {
            return datatype != null && !datatype.isBlank() ? datatype : RDF_LANG_STRING;
        }
        if (datatype == null || datatype.isBlank()) {
            return XSD_STRING;
        }
        return datatype;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static final String RDF_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LiteralValue that)) return false;
        return lex.equals(that.lex)
                && Objects.equals(datatype, that.datatype)
                && Objects.equals(lang, that.lang)
                && Objects.equals(direction, that.direction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lex, datatype, lang, direction);
    }
}
