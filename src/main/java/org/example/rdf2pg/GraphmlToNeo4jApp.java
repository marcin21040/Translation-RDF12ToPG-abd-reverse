package org.example.rdf2pg;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Import a GraphML property graph file into Neo4j.
 * <p>
 * Usage: GraphmlToNeo4jApp &lt;graphml-file&gt;
 */
public class GraphmlToNeo4jApp {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: GraphmlToNeo4jApp <graphml-file>");
            System.exit(1);
        }
        Path graphml = Paths.get(args[0]);
        if (!graphml.toFile().exists()) {
            System.err.println("File not found: " + graphml);
            System.exit(1);
        }

        Neo4jConfig config = Neo4jConfig.load();
        if (config.getPassword() == null || config.getPassword().isBlank()) {
            System.err.println("Error: set neo4j.password or NEO4J_PASSWORD");
            System.exit(1);
        }

        System.err.println("Importing GraphML: " + graphml.toAbsolutePath());
        System.err.println("Neo4j: " + config.getUri());

        try (GraphmlStreamingImporter importer = GraphmlStreamingImporter.create(
                config.getUri(), config.getUser(), config.getPassword(),
                msg -> System.err.println("  " + msg))) {
            GraphmlStreamingImporter.ImportStats stats = importer.importFile(graphml);
            System.err.println("Done. Nodes: " + stats.nodes + ", edges: " + stats.edges);
        }
    }
}
