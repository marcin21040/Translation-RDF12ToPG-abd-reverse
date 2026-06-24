package org.example.rdf2pg;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * Verifies two RDF 1.2 N-Triples files using canonical or strict comparison.
 */
public class CanonicalRdfVerifier {

    public static void main(String[] args) throws Exception {
        boolean strict = false;
        int argIdx = 0;
        if (args.length >= 1 && "--strict".equals(args[0])) {
            strict = true;
            argIdx = 1;
        } else if (args.length >= 1 && "--canonical".equals(args[0])) {
            argIdx = 1;
        }
        if (args.length - argIdx < 2) {
            System.err.println("Usage: CanonicalRdfVerifier [--canonical|--strict] <original.nt> <roundtrip.nt>");
            System.exit(1);
        }
        Path original = Paths.get(args[argIdx]);
        Path roundtrip = Paths.get(args[argIdx + 1]);

        if (strict) {
            verifyStrict(original, roundtrip);
        } else {
            verifyCanonical(original, roundtrip);
        }
    }

    private static void verifyCanonical(Path original, Path roundtrip) throws Exception {
        System.err.println("Streaming canonical compare: " + original);
        System.err.println("Streaming canonical compare: " + roundtrip);

        StreamingCanonicalNtComparer.CompareResult result =
                StreamingCanonicalNtComparer.compareCanonicalFiles(original, roundtrip);
        System.err.println("  lines: " + result.countA);
        System.err.println("  lines: " + result.countB);

        if (result.equal) {
            System.out.println("OK — files are canonically equal.");
            System.out.println("  Canonical lines: " + result.countA);
            System.out.println();
            System.out.println("What was checked:");
            System.out.println("  - Streaming canonical N-Triples (W3C-style layout per triple):");
            System.out.println("    single spaces, no VERSION, xsd:string without ^^, escaped literals.");
            System.out.println("  - Reifier blank node labels ignored; annotations tied to reified (S,P,O).");
            System.out.println("  - Plain asserted triples compared by (S,P,O) canonical form.");
            System.out.println("  - Line order ignored; identical canonical lines counted once (duplicate");
            System.out.println("    annotation lines in source NT are not preserved through Jena import).");
            System.out.println("  - Reifier annotations merged by blank-node id (YAGO may repeat the same id");
            System.out.println("    in non-contiguous blocks; matches Jena import grouping).");
            System.exit(0);
        }

        printDiff("canonical", (int) result.countA, (int) result.countB, result.onlyInA, result.onlyInB);
        System.exit(1);
    }

    private static void verifyStrict(Path original, Path roundtrip) throws Exception {
        System.err.println("Strict line compare: " + original);
        Set<String> linesA = StreamingCanonicalNtComparer.strictLines(original);
        System.err.println("  lines: " + linesA.size());

        System.err.println("Strict line compare: " + roundtrip);
        Set<String> linesB = StreamingCanonicalNtComparer.strictLines(roundtrip);
        System.err.println("  lines: " + linesB.size());

        if (linesA.equals(linesB)) {
            System.out.println("OK — files are strictly equal (including reifier IDs and asserted triples).");
            System.out.println("  Lines: " + linesA.size());
            System.exit(0);
        }

        List<String> onlyA = StreamingCanonicalNtComparer.diff(linesA, linesB);
        List<String> onlyB = StreamingCanonicalNtComparer.diff(linesB, linesA);
        printDiff("strict", linesA.size(), linesB.size(), onlyA, onlyB);
        System.exit(1);
    }

    private static void printDiff(String mode, int sizeA, int sizeB, List<String> onlyA, List<String> onlyB) {
        System.out.println("DIFF — " + mode + " N-Triples mismatch.");
        System.out.println("  Original:   " + sizeA + " lines");
        System.out.println("  Round-trip: " + sizeB + " lines");
        if (!onlyA.isEmpty()) {
            System.err.println("  Only in original (" + onlyA.size() + "), first lines:");
            for (int i = 0; i < Math.min(3, onlyA.size()); i++) {
                System.err.println("    " + truncate(onlyA.get(i), 120));
            }
        }
        if (!onlyB.isEmpty()) {
            System.err.println("  Only in round-trip (" + onlyB.size() + "), first lines:");
            for (int i = 0; i < Math.min(3, onlyB.size()); i++) {
                System.err.println("    " + truncate(onlyB.get(i), 120));
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
