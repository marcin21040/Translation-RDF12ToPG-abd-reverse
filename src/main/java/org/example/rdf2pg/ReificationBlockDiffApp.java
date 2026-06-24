package org.example.rdf2pg;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two NT files by ReificationBlock canonical keys and streaming canonical lines.
 */
public class ReificationBlockDiffApp {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ReificationBlockDiffApp <original.nt> <roundtrip.nt>");
            System.exit(1);
        }
        Path a = Paths.get(args[0]);
        Path b = Paths.get(args[1]);

        System.err.println("Parsing blocks: " + a);
        Set<ReificationBlock> setA = new TreeSet<>(NtReificationParser.parse(a));
        System.err.println("  " + setA.size());

        System.err.println("Parsing blocks: " + b);
        Set<ReificationBlock> setB = new TreeSet<>(NtReificationParser.parse(b));
        System.err.println("  " + setB.size());

        List<String> onlyBlockA = new ArrayList<>();
        for (ReificationBlock block : setA) {
            if (!setB.contains(block)) {
                onlyBlockA.add(block.canonicalKey());
            }
        }
        List<String> onlyBlockB = new ArrayList<>();
        for (ReificationBlock block : setB) {
            if (!setA.contains(block)) {
                onlyBlockB.add(block.canonicalKey());
            }
        }

        System.out.println("Block sets: A=" + setA.size() + " B=" + setB.size());
        System.out.println("Only in original (blocks): " + onlyBlockA.size());
        System.out.println("Only in round-trip (blocks): " + onlyBlockB.size());

        if (!onlyBlockA.isEmpty()) {
            System.out.println("Sample only in original:");
            for (int i = 0; i < Math.min(5, onlyBlockA.size()); i++) {
                System.out.println("  " + onlyBlockA.get(i));
            }
        }
        if (!onlyBlockB.isEmpty()) {
            System.out.println("Sample only in round-trip:");
            for (int i = 0; i < Math.min(5, onlyBlockB.size()); i++) {
                System.out.println("  " + onlyBlockB.get(i));
            }
        }

        System.err.println("Canonical lines: " + a);
        Set<String> canonA = StreamingCanonicalNtComparer.canonicalLines(a);
        System.err.println("  " + canonA.size());
        System.err.println("Canonical lines: " + b);
        Set<String> canonB = StreamingCanonicalNtComparer.canonicalLines(b);
        System.err.println("  " + canonB.size());

        List<String> onlyCanonA = StreamingCanonicalNtComparer.diff(canonA, canonB);
        List<String> onlyCanonB = StreamingCanonicalNtComparer.diff(canonB, canonA);
        System.out.println("Only in original (canonical): " + onlyCanonA.size());
        System.out.println("Only in round-trip (canonical): " + onlyCanonB.size());
        if (!onlyCanonA.isEmpty()) {
            System.out.println("Sample canonical only in original:");
            for (int i = 0; i < Math.min(5, onlyCanonA.size()); i++) {
                System.out.println("  " + onlyCanonA.get(i));
            }
        }
        if (!onlyCanonB.isEmpty()) {
            System.out.println("Sample canonical only in round-trip:");
            for (int i = 0; i < Math.min(5, onlyCanonB.size()); i++) {
                System.out.println("  " + onlyCanonB.get(i));
            }
        }
    }
}
