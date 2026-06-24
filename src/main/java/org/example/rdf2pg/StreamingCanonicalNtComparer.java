package org.example.rdf2pg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical N-Triples comparison. Small files use in-memory sets; large files use
 * external sort + streaming merge (constant memory).
 */
final class StreamingCanonicalNtComparer {

    private static final String REIFIES = "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";
    private static final long IN_MEMORY_THRESHOLD = 500_000L;
    private static final int MAX_DIFF_SAMPLES = 3;
    private static final Pattern REIFIES_LINE = Pattern.compile(
            "^(_:\\S+)\\s+<" + Pattern.quote(REIFIES) + ">\\s*<<\\(\\s*<([^>]+)>\\s*<([^>]+)>\\s+(.+?)\\s*\\)>>\\s*\\.$");
    private static final Pattern ANNOT_LINE = Pattern.compile(
            "^(_:\\S+)\\s+<([^>]+)>\\s+(.+?)\\s*\\.$");

    private StreamingCanonicalNtComparer() {}

    static final class CompareResult {
        final boolean equal;
        final long countA;
        final long countB;
        final List<String> onlyInA;
        final List<String> onlyInB;

        CompareResult(boolean equal, long countA, long countB, List<String> onlyInA, List<String> onlyInB) {
            this.equal = equal;
            this.countA = countA;
            this.countB = countB;
            this.onlyInA = onlyInA;
            this.onlyInB = onlyInB;
        }
    }

    static CompareResult compareCanonicalFiles(Path original, Path roundtrip) throws Exception {
        long estA = estimateLineCount(original);
        long estB = estimateLineCount(roundtrip);
        if (estA <= IN_MEMORY_THRESHOLD && estB <= IN_MEMORY_THRESHOLD) {
            Set<String> canonA = canonicalLines(original);
            Set<String> canonB = canonicalLines(roundtrip);
            boolean equal = canonA.equals(canonB);
            return new CompareResult(
                    equal,
                    canonA.size(),
                    canonB.size(),
                    equal ? List.of() : diff(canonA, canonB),
                    equal ? List.of() : diff(canonB, canonA));
        }
        return compareViaExternalSort(original, roundtrip);
    }

    private static long estimateLineCount(Path path) throws IOException {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    private static CompareResult compareViaExternalSort(Path original, Path roundtrip) throws Exception {
        Path tempDir = Files.createTempDirectory("rdf-canonical-compare-");
        try {
            Path unsortedA = tempDir.resolve("a.unsorted");
            Path unsortedB = tempDir.resolve("b.unsorted");
            Path sortedA = tempDir.resolve("a.sorted");
            Path sortedB = tempDir.resolve("b.sorted");

            long countA = writeCanonicalLines(original, unsortedA);
            long countB = writeCanonicalLines(roundtrip, unsortedB);
            externalSortUnique(unsortedA, sortedA);
            externalSortUnique(unsortedB, sortedB);
            long uniqueA = countLines(sortedA);
            long uniqueB = countLines(sortedB);
            CompareResult result = diffSortedFiles(sortedA, sortedB, uniqueA, uniqueB);
            return result;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static long writeCanonicalLines(Path path, Path out) throws Exception {
        Map<String, List<String>> reifierBlocks = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> reifierAnnotations = new HashMap<>();
        List<String> plainAsserted = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("VERSION")) {
                    continue;
                }

                Matcher m = REIFIES_LINE.matcher(line);
                if (m.matches()) {
                    String reifier = m.group(1);
                    String block = blockIdentity(m.group(2), m.group(3), m.group(4).trim());
                    reifierBlocks.computeIfAbsent(reifier, k -> new ArrayList<>()).add(block);
                    reifierAnnotations.computeIfAbsent(reifier, k -> new LinkedHashMap<>());
                    continue;
                }

                Matcher a = ANNOT_LINE.matcher(line);
                if (a.matches()) {
                    String reifier = a.group(1);
                    String pred = a.group(2);
                    if (REIFIES.equals(pred)) {
                        continue;
                    }
                    String obj = canonicalObject(a.group(3).trim());
                    reifierAnnotations
                            .computeIfAbsent(reifier, k -> new LinkedHashMap<>())
                            .computeIfAbsent(pred, k -> new ArrayList<>())
                            .add(obj);
                    continue;
                }

                plainAsserted.add(canonicalPlainLine(line));
            }
        }

        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (String line : plainAsserted) {
                writer.write(line);
                writer.newLine();
                count++;
            }
            for (Map.Entry<String, List<String>> entry : reifierBlocks.entrySet()) {
                Map<String, List<String>> annotations = reifierAnnotations.getOrDefault(entry.getKey(), Map.of());
                count += writeReifierBlocks(writer, entry.getValue(), annotations);
            }
        }
        return count;
    }

    private static long writeReifierBlocks(BufferedWriter writer,
                                           List<String> blocks,
                                           Map<String, List<String>> annotations) throws IOException {
        long count = 0;
        for (String block : blocks) {
            writer.write("<");
            writer.write(REIFIES);
            writer.write("> <<( ");
            writer.write(block);
            writer.write(" )>> .");
            writer.newLine();
            count++;
            for (Map.Entry<String, List<String>> ann : annotations.entrySet()) {
                for (String obj : ann.getValue()) {
                    writer.write(block);
                    writer.write(" <");
                    writer.write(ann.getKey());
                    writer.write("> ");
                    writer.write(obj);
                    writer.write(" .");
                    writer.newLine();
                    count++;
                }
            }
        }
        return count;
    }

    private static long flushReifier(BufferedWriter writer,
                                     List<String> blocks,
                                     Map<String, List<String>> annotations) throws IOException {
        return writeReifierBlocks(writer, blocks, annotations);
    }

    private static void externalSort(Path input, Path output) throws Exception {
        runSort(input, output, false);
    }

    /** Unique canonical lines — duplicate annotation lines in source NT are collapsed (Jena import does the same). */
    private static void externalSortUnique(Path input, Path output) throws Exception {
        runSort(input, output, true);
    }

    private static void runSort(Path input, Path output, boolean unique) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("sort");
        if (unique) {
            cmd.add("-u");
        }
        cmd.add("--buffer-size=512M");
        cmd.add("-T");
        cmd.add(System.getProperty("java.io.tmpdir"));
        cmd.add("-o");
        cmd.add(output.toString());
        cmd.add(input.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String sortOut = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("external sort failed (exit " + code + "): " + sortOut);
        }
    }

    private static long countLines(Path path) throws IOException {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    private static CompareResult diffSortedFiles(Path sortedA, Path sortedB, long countA, long countB)
            throws IOException {
        List<String> onlyA = new ArrayList<>();
        List<String> onlyB = new ArrayList<>();
        boolean equal = true;

        try (BufferedReader a = Files.newBufferedReader(sortedA, StandardCharsets.UTF_8);
             BufferedReader b = Files.newBufferedReader(sortedB, StandardCharsets.UTF_8)) {
            String lineA = readNonEmpty(a);
            String lineB = readNonEmpty(b);
            while (lineA != null || lineB != null) {
                if (lineA == null) {
                    equal = false;
                    if (onlyB.size() < MAX_DIFF_SAMPLES) {
                        onlyB.add(lineB);
                    }
                    lineB = readNonEmpty(b);
                    continue;
                }
                if (lineB == null) {
                    equal = false;
                    if (onlyA.size() < MAX_DIFF_SAMPLES) {
                        onlyA.add(lineA);
                    }
                    lineA = readNonEmpty(a);
                    continue;
                }
                int cmp = lineA.compareTo(lineB);
                if (cmp < 0) {
                    equal = false;
                    if (onlyA.size() < MAX_DIFF_SAMPLES) {
                        onlyA.add(lineA);
                    }
                    lineA = readNonEmpty(a);
                } else if (cmp > 0) {
                    equal = false;
                    if (onlyB.size() < MAX_DIFF_SAMPLES) {
                        onlyB.add(lineB);
                    }
                    lineB = readNonEmpty(b);
                } else {
                    lineA = readNonEmpty(a);
                    lineB = readNonEmpty(b);
                }
            }
        }
        return new CompareResult(equal, countA, countB, onlyA, onlyB);
    }

    private static String readNonEmpty(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    static Set<String> canonicalLines(Path path) throws Exception {
        Map<String, List<String>> reifierBlocks = new HashMap<>();
        Map<String, Map<String, List<String>>> reifierAnnotations = new HashMap<>();
        List<String> reifiesCanonical = new ArrayList<>();
        Set<String> plainAsserted = new TreeSet<>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("VERSION")) {
                    continue;
                }

                Matcher m = REIFIES_LINE.matcher(line);
                if (m.matches()) {
                    String reifier = m.group(1);
                    String block = blockIdentity(m.group(2), m.group(3), m.group(4).trim());
                    reifierBlocks.computeIfAbsent(reifier, k -> new ArrayList<>()).add(block);
                    reifierAnnotations.computeIfAbsent(reifier, k -> new LinkedHashMap<>());
                    reifiesCanonical.add("<" + REIFIES + "> <<( " + block + " )>> .");
                    continue;
                }

                Matcher a = ANNOT_LINE.matcher(line);
                if (a.matches()) {
                    String reifier = a.group(1);
                    String pred = a.group(2);
                    if (REIFIES.equals(pred)) {
                        continue;
                    }
                    String obj = canonicalObject(a.group(3).trim());
                    reifierAnnotations
                            .computeIfAbsent(reifier, k -> new LinkedHashMap<>())
                            .computeIfAbsent(pred, k -> new ArrayList<>())
                            .add(obj);
                    continue;
                }

                plainAsserted.add(canonicalPlainLine(line));
            }
        }

        Set<String> lines = new TreeSet<>(plainAsserted);
        lines.addAll(reifiesCanonical);
        for (Map.Entry<String, List<String>> entry : reifierBlocks.entrySet()) {
            String reifier = entry.getKey();
            Map<String, List<String>> annotations = reifierAnnotations.getOrDefault(reifier, Map.of());
            for (String block : entry.getValue()) {
                for (Map.Entry<String, List<String>> ann : annotations.entrySet()) {
                    for (String obj : ann.getValue()) {
                        lines.add(block + " <" + ann.getKey() + "> " + obj + " .");
                    }
                }
            }
        }
        return lines;
    }

    static Set<String> strictLines(Path path) throws Exception {
        Set<String> lines = new TreeSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("VERSION")) {
                    continue;
                }
                lines.add(line);
            }
        }
        return lines;
    }

    private static String canonicalPlainLine(String line) {
        int dot = line.lastIndexOf(" .");
        if (dot < 0) {
            return line;
        }
        String body = line.substring(0, dot);
        int pStart = body.indexOf(" <");
        if (pStart < 0) {
            return line;
        }
        String subj = body.substring(0, pStart).trim();
        int pEnd = body.indexOf('>', pStart);
        String pred = body.substring(pStart + 2, pEnd);
        String objRaw = body.substring(pEnd + 1).trim();
        return subj + " <" + pred + "> " + canonicalObject(objRaw) + " .";
    }

    private static String blockIdentity(String subject, String predicate, String objectRaw) {
        return "<" + subject + "> <" + predicate + "> " + canonicalObject(objectRaw.trim());
    }

    private static String canonicalObject(String raw) {
        if (raw.startsWith("<") && raw.endsWith(">")) {
            return raw;
        }
        if (raw.startsWith("_:")) {
            return raw;
        }
        return canonicalLiteral(raw);
    }

    private static String canonicalLiteral(String term) {
        LiteralValue lit = LiteralTermParser.parse(term);
        String dt = lit.getDatatype();
        String lex = lit.getLex();
        if (lit.hasLang()) {
            StringBuilder sb = new StringBuilder();
            sb.append('"').append(escapeLex(lex)).append('"').append('@').append(lit.getLang());
            if (lit.getDirection() != null && !lit.getDirection().isBlank()) {
                sb.append("--").append(lit.getDirection());
            }
            return sb.toString();
        }
        if (dt == null || dt.isBlank() || XSD_STRING.equals(dt)) {
            return "\"" + escapeLex(lex) + "\"";
        }
        return "\"" + escapeLex(lex) + "\"^^<" + dt + ">";
    }

    private static String escapeLex(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static List<String> diff(Set<String> a, Set<String> b) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (!b.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
