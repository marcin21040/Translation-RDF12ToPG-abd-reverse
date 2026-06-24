package org.example.rdf2pg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prosty kodek JSON dla mapy annotacji (URI → wartość) na relacjach Neo4j.
 * Bez zewnętrznych bibliotek — wartości jako string/number/boolean/list.
 */
public final class AnnotationJsonCodec {

    public static final String ANNOTATIONS_PROP = "annotations";

    private AnnotationJsonCodec() {}

    public static String encode(Map<String, Object> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : annotations.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":");
            appendValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    public static Map<String, Object> decode(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object v = new Parser(json).parseValue();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static void appendValue(StringBuilder sb, Object v) {
        if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (item == null) continue;
                if (!first) sb.append(',');
                first = false;
                appendValue(sb, item);
            }
            sb.append(']');
            return;
        }
        if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(n);
        } else {
            sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Minimalny parser JSON dla obiektów {k:v} gdzie v ∈ (string|number|boolean|null|array).
     * Wystarcza do round-trip annotacji bez dokładania zależności.
     */
    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) {
            this.s = s.trim();
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { i = Math.min(i + 4, s.length()); return null; } // null
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek('}')) { i++; return out; }
            while (i < s.length()) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                out.put(key, val);
                skipWs();
                if (peek('}')) { i++; break; }
                expect(',');
            }
            return out;
        }

        List<Object> parseArray() {
            List<Object> out = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek(']')) { i++; return out; }
            while (i < s.length()) {
                Object v = parseValue();
                out.add(v);
                skipWs();
                if (peek(']')) { i++; break; }
                expect(',');
            }
            return out;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    if (n == '"' || n == '\\' || n == '/') sb.append(n);
                    else if (n == 'b') sb.append('\b');
                    else if (n == 'f') sb.append('\f');
                    else if (n == 'n') sb.append('\n');
                    else if (n == 'r') sb.append('\r');
                    else if (n == 't') sb.append('\t');
                    else sb.append(n);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            return Boolean.FALSE;
        }

        Object parseNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '.' || c == 'e' || c == 'E' || c == '+') {
                    i++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) return "";
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                try { return Double.parseDouble(num); } catch (Exception e) { return num; }
            }
            try { return Long.parseLong(num); } catch (Exception e) { return num; }
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            skipWs();
            if (i < s.length() && s.charAt(i) == c) { i++; return; }
            // best-effort: move forward to avoid infinite loop
            if (i < s.length()) i++;
        }
    }
}
