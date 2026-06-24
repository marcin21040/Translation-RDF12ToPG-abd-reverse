package org.example.rdf2pg;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Import RDF 1.2 N-Triples into Neo4j (streaming).
 * <p>
 * Usage: StreamingRdf12ToNeo4jApp &lt;input.nt&gt;
 */
public class StreamingRdf12ToNeo4jApp {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: StreamingRdf12ToNeo4jApp <input.nt>");
            System.exit(1);
        }
        Path inPath = Paths.get(args[0]);
        if (!inPath.toFile().exists()) {
            System.err.println("File not found: " + inPath);
            System.exit(1);
        }

        Neo4jConfig config = Neo4jConfig.load();
        if (config.getPassword() == null || config.getPassword().isBlank()) {
            System.err.println("Error: set neo4j.password or NEO4J_PASSWORD");
            System.exit(1);
        }

        System.err.println("Import RDF 1.2 -> Neo4j: " + inPath.toAbsolutePath());
        System.err.println("Neo4j: " + config.getUri());

        try (StreamingRdf12ToNeo4jImporter importer = StreamingRdf12ToNeo4jImporter.create(
                config.getUri(), config.getUser(), config.getPassword(),
                msg -> System.err.println("  " + msg))) {
            StreamingRdf12ToNeo4jImporter.ImportStats stats = importer.importFile(inPath);
            System.err.println("Done. Blocks: " + stats.blocks + ", edges: " + stats.edges
                    + ", node props: " + stats.nodeProps);
        }
    }
}
