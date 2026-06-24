package org.example.rdf2pg;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zapis RDF 1.2 do N-Triples z nagłówkiem {@code VERSION "1.2"}.
 */
public final class Rdf12NtWriter {

    private static final String RDF_REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private Rdf12NtWriter() {}

    public static void writeExport(Rdf12ExportBundle bundle, Path path) throws IOException {
        AtomicLong idGen = new AtomicLong(0);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("VERSION \"1.2\"");
            w.newLine();
            for (String line : bundle.getAssertedTriples()) {
                w.write(line);
                w.newLine();
            }
            for (ReificationBlock b : bundle.getReificationBlocks()) {
                String reifier = resolveReifier(b.getReifierId(), idGen);
                writeBlock(w, reifier, b);
            }
        }
    }

    public static void writeBlocks(List<ReificationBlock> blocks, Path path) throws IOException {
        Rdf12ExportBundle bundle = new Rdf12ExportBundle();
        blocks.forEach(bundle::addReificationBlock);
        writeExport(bundle, path);
    }

    public static void writeBlock(BufferedWriter w, String reifier, ReificationBlock b) throws IOException {
        writeBlockInternal(w, reifier, b);
    }

    public static void writePlainTriple(BufferedWriter w, String subject, String predicate, String objectUri) throws IOException {
        w.write(RdfResources.writeSubjectOrObject(subject));
        w.write(" <");
        w.write(predicate);
        w.write("> ");
        w.write(RdfResources.writeSubjectOrObject(objectUri));
        w.write(" .");
        w.newLine();
    }

    public static String formatLiteralTerm(LiteralValue lit) {
        if (lit == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(escapeLex(lit.getLex())).append('"');
        if (lit.hasLang()) {
            sb.append('@').append(lit.getLang());
            if (lit.getDirection() != null && !lit.getDirection().isBlank()) {
                sb.append("--").append(lit.getDirection());
            }
        } else if (lit.getDatatype() != null && !lit.getDatatype().isBlank()
                && !PgUris.XSD_STRING.equals(lit.getDatatype())) {
            sb.append("^^<").append(lit.getDatatype()).append('>');
        }
        return sb.toString();
    }

    private static String resolveReifier(String reifierId, AtomicLong idGen) {
        if (reifierId != null && !reifierId.isBlank()) {
            return reifierId.startsWith("_:") ? reifierId : reifierId;
        }
        return "_:rt" + idGen.getAndIncrement();
    }

    private static void writeBlockInternal(BufferedWriter w, String reifier, ReificationBlock b) throws IOException {
        w.write(reifier);
        w.write(" <");
        w.write(RDF_REIFIES);
        w.write("> <<( ");
        w.write(RdfResources.writeSubjectOrObject(b.getSubjectUri()));
        w.write(" <");
        w.write(b.getPredicateUri());
        w.write("> ");
        if (b.isObjectLiteral()) {
            w.write(formatLiteralTerm(b.getObjectLiteral()));
        } else {
            w.write(RdfResources.writeSubjectOrObject(b.getObjectUri()));
        }
        w.write(" )>> .");
        w.newLine();

        for (Map.Entry<String, List<String>> ann : b.getAnnotations().entrySet()) {
            List<String> vals = ann.getValue();
            if (vals == null) continue;
            for (String v : vals) {
                if (v == null) continue;
                w.write(reifier);
                w.write(" <");
                w.write(ann.getKey());
                w.write("> ");
                writeAnnotationValue(w, v, ann.getKey());
                w.write(" .");
                w.newLine();
            }
        }
    }

    private static void writeAnnotationValue(BufferedWriter w, String value, String predicateUri) throws IOException {
        String dt = inferDatatype(value, predicateUri);
        if (dt != null) {
            w.write('"');
            w.write(escapeLex(value));
            w.write('"');
            w.write("^^<");
            w.write(dt);
            w.write('>');
        } else {
            w.write('"');
            w.write(escapeLex(value));
            w.write('"');
        }
    }

    private static String inferDatatype(String value, String predicateUri) {
        if (value == null) return null;
        if (predicateUri != null && predicateUri.contains("schema.org")) {
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return XSD + "date";
            }
            if (value.matches("\\d{4}-\\d{2}")) {
                return XSD + "gYearMonth";
            }
            if (value.matches("\\d{4}")) {
                return XSD + "gYear";
            }
        }
        return null;
    }

    private static String escapeLex(String s) {
        return NtLiteralEscape.escape(s);
    }
}
