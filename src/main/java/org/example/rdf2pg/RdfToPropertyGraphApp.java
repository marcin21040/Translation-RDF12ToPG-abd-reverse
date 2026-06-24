package org.example.rdf2pg;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Uruchomienie translacji RDF 1.2 → graf własności i zapis do Neo4j.
 * <p>
 * Argument: opcjonalnie ścieżka do pliku N-Triples (domyślnie {@code wynik.nt}).
 * Konfiguracja Neo4j: {@code neo4j.properties} lub {@code NEO4J_URI}, {@code NEO4J_USER}, {@code NEO4J_PASSWORD}.
 */
public class RdfToPropertyGraphApp {

    public static void main(String[] args) throws IOException {
        Path inPath = args.length >= 1 ? Paths.get(args[0]) : Paths.get("wynik.nt");

        if (!inPath.toFile().exists()) {
            System.err.println("Plik wejściowy nie istnieje: " + inPath);
            System.exit(1);
        }

        System.err.println("Wczytywanie RDF: " + inPath);
        Model model = ModelFactory.createDefaultModel();
        NtRdf12Compat.readIntoModel(model, inPath);
        System.err.println("Triples wczytane: " + model.size());

        System.err.println("Translacja RDF → graf własności...");
        Map<TripleKey, String> reifierIds = buildReifierIdMap(inPath);
        RdfToPropertyGraphTranslator translator = new RdfToPropertyGraphTranslator();
        PropertyGraph pg;
        try {
            pg = translator.translate(model, reifierIds);
        } catch (InputProfileViolationException e) {
            System.err.println("Błąd profilu wejściowego RDF: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.err.println("Węzły: " + pg.getNodeCount() + ", krawędzie: " + pg.getEdgeCount());

        Neo4jConfig config = Neo4jConfig.load();
        if (config.getPassword() == null || config.getPassword().isBlank()) {
            System.err.println("Błąd: ustaw neo4j.password w neo4j.properties lub zmienną NEO4J_PASSWORD");
            System.exit(1);
        }
        System.err.println("Zapis do Neo4j: " + config.getUri());
        try (PropertyGraphNeo4jWriter writer = PropertyGraphNeo4jWriter.create(
                config.getUri(), config.getUser(), config.getPassword(),
                msg -> System.err.println("  " + msg))) {
            writer.write(pg);
        } catch (RuntimeException e) {
            System.err.println("Błąd zapisu do Neo4j: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("  Przyczyna: " + e.getCause().getMessage());
            }
            System.exit(1);
        }

        System.err.println("Gotowe.");
    }

    private static Map<TripleKey, String> buildReifierIdMap(Path ntPath) throws IOException {
        Map<TripleKey, String> map = new HashMap<>();
        for (ReificationBlock block : NtReificationParser.parse(ntPath)) {
            if (block.getReifierId() != null) {
                map.put(TripleKey.fromBlock(block), block.getReifierId());
            }
        }
        return map;
    }
}
