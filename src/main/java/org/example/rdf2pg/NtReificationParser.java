package org.example.rdf2pg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser bloków reifikacji z pliku N-Triples RDF 1.2 (format YAGO / wynik.nt).
 */
public final class NtReificationParser {

    private static final String REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies";
    private static final Pattern REIFIES_LINE = Pattern.compile(
            "^(_:\\S+)\\s+<" + Pattern.quote(REIFIES) + ">\\s*<<\\(\\s*<([^>]+)>\\s*<([^>]+)>\\s+(.+?)\\s*\\)>>\\s*\\.$");
    private static final Pattern PLAIN_TRIPLE = Pattern.compile(
            "^(<[^>]+>|_:\\S+)\\s+<([^>]+)>\\s+(.+?)\\s*\\.$");
    private static final Pattern ANNOT_LINE = Pattern.compile(
            "^(_:\\S+)\\s+<([^>]+)>\\s+(.+?)\\s*\\.$");

    private NtReificationParser() {}

    public static List<ReificationBlock> parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Map<String, List<PartialBlock>> partial = new LinkedHashMap<>();
        List<PartialBlock> order = new ArrayList<>();
        Map<String, Map<String, List<String>>> sharedAnn = new LinkedHashMap<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("VERSION") || line.startsWith("#")) {
                continue;
            }
            Matcher m = REIFIES_LINE.matcher(line);
            if (m.matches()) {
                String id = m.group(1);
                PartialBlock pb = new PartialBlock(id, m.group(2), m.group(3), m.group(4));
                partial.computeIfAbsent(id, k -> new ArrayList<>()).add(pb);
                order.add(pb);
                sharedAnn.computeIfAbsent(id, k -> new LinkedHashMap<>());
                continue;
            }
            Matcher a = ANNOT_LINE.matcher(line);
            if (a.matches()) {
                String id = a.group(1);
                Map<String, List<String>> ann = sharedAnn.computeIfAbsent(id, k -> new LinkedHashMap<>());
                ann.computeIfAbsent(a.group(2), k -> new ArrayList<>())
                        .add(literalLex(parseObjectTerm(a.group(3))));
            }
        }

        List<ReificationBlock> blocks = new ArrayList<>(order.size());
        for (PartialBlock pb : order) {
            Map<String, List<String>> ann = sharedAnn.get(pb.id);
            blocks.add(pb.toBlock(ann != null ? ann : Map.of()));
        }
        return blocks;
    }

    private static String parseObjectTerm(String objRaw) {
        return objRaw.trim();
    }

    private static final class PartialBlock {
        final String id;
        final String subject;
        final String predicate;
        final String objectRaw;

        PartialBlock(String id, String subject, String predicate, String objectRaw) {
            this.id = id;
            this.subject = subject;
            this.predicate = predicate;
            this.objectRaw = objectRaw;
        }

        ReificationBlock toBlock(Map<String, List<String>> annotations) {
            String objUri = null;
            LiteralValue lit = null;
            String trimmed = objectRaw.trim();
            if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
                objUri = trimmed.substring(1, trimmed.length() - 1);
            } else if (trimmed.startsWith("_:")) {
                objUri = trimmed;
            } else {
                lit = LiteralTermParser.parse(trimmed);
            }
            return new ReificationBlock(subject, predicate, objUri, lit, annotations, id, false, true);
        }
    }

    private static String literalLex(String term) {
        return LiteralTermParser.parse(term).getLex();
    }
}
