package org.example.rdf2pg;

/** Parses N-Triples literal terms (lex, @lang, ^^datatype, direction). */
public final class LiteralTermParser {

    private LiteralTermParser() {}

    public static LiteralValue parse(String term) {
        if (term == null) {
            return new LiteralValue("", null, null, null);
        }
        String t = term.trim();
        if (t.startsWith("<") && t.endsWith(">")) {
            return new LiteralValue(t, null, null, null);
        }

        String lex;
        String lang = null;
        String direction = null;
        String datatype = null;

        int dtIdx = t.indexOf("^^");
        String main = dtIdx >= 0 ? t.substring(0, dtIdx).trim() : t;
        if (dtIdx >= 0) {
            datatype = parseDatatype(t.substring(dtIdx + 2).trim());
        }

        if (main.startsWith("\"")) {
            int close = findClosingQuote(main, 0);
            lex = unescape(main.substring(1, close));
            String tail = main.substring(close + 1).trim();
            if (tail.startsWith("@")) {
                String langPart = tail.substring(1);
                int dirIdx = langPart.indexOf("--");
                if (dirIdx > 0) {
                    lang = langPart.substring(0, dirIdx);
                    direction = langPart.substring(dirIdx + 2);
                } else {
                    lang = langPart;
                }
            }
        } else {
            lex = main;
        }

        return new LiteralValue(lex, datatype, lang, direction);
    }

    private static int findClosingQuote(String s, int from) {
        for (int i = from + 1; i < s.length(); i++) {
            if (s.charAt(i) == '"' && s.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return s.length() - 1;
    }

    private static String parseDatatype(String tail) {
        if (tail == null || tail.isBlank() || "null".equalsIgnoreCase(tail)) {
            return null;
        }
        if (tail.startsWith("<") && tail.endsWith(">")) {
            return tail.substring(1, tail.length() - 1);
        }
        return tail;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }
}
