package org.example.rdf2pg;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory round-trip: NT → PG → NT → compare (no Neo4j).
 */
public class RoundTripInMemoryApp {

    public static void main(String[] args) throws Exception {
        Path inPath = args.length >= 1 ? Paths.get(args[0]) : Paths.get("wynik-sample-42.nt");
        Path outPath = args.length >= 2 ? Paths.get(args[1]) : Paths.get("wynik-roundtrip-42.nt");

        Model model = ModelFactory.createDefaultModel();
        NtRdf12Compat.readIntoModel(model, inPath);

        PropertyGraph pg;
        try {
            pg = new RdfToPropertyGraphTranslator().translate(model, reifierMap(inPath));
        } catch (InputProfileViolationException e) {
            System.err.println("Input profile error: " + e.getMessage());
            System.exit(1);
            return;
        }
        Rdf12ExportBundle bundle = new PropertyGraphToRdf12Translator().translate(pg);
        Rdf12NtWriter.writeExport(bundle, outPath);

        List<ReificationBlock> orig = NtReificationParser.parse(inPath);
        List<ReificationBlock> back = NtReificationParser.parse(outPath);
        System.err.println("Original: " + orig.size() + " blocks, round-trip: " + back.size());

        var setO = new java.util.TreeSet<>(orig);
        var setB = new java.util.TreeSet<>(back);
        if (setO.equals(setB)) {
            System.out.println("OK — in-memory round-trip is semantically equal.");
        } else {
            System.err.println("ERROR — files differ.");
            System.exit(1);
        }
    }

    private static Map<TripleKey, String> reifierMap(Path ntPath) throws Exception {
        Map<TripleKey, String> map = new HashMap<>();
        for (ReificationBlock block : NtReificationParser.parse(ntPath)) {
            if (block.getReifierId() != null) {
                map.put(TripleKey.fromBlock(block), block.getReifierId());
            }
        }
        return map;
    }
}
