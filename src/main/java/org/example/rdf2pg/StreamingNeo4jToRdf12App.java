package org.example.rdf2pg;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Export Neo4j property graph to RDF 1.2 N-Triples (streaming).
 * <p>
 * Usage: StreamingNeo4jToRdf12App [output.nt]
 */
public class StreamingNeo4jToRdf12App {

    public static void main(String[] args) throws Exception {
        Path outPath = args.length >= 1 ? Paths.get(args[0]) : Paths.get("twitch-rdf.nt");

        Neo4jConfig config = Neo4jConfig.load();
        if (config.getPassword() == null || config.getPassword().isBlank()) {
            System.err.println("Error: set neo4j.password or NEO4J_PASSWORD");
            System.exit(1);
        }

        System.err.println("Export Neo4j -> RDF 1.2: " + outPath.toAbsolutePath());
        System.err.println("Neo4j: " + config.getUri());

        try (StreamingNeo4jToRdf12Exporter exporter = StreamingNeo4jToRdf12Exporter.create(
                config.getUri(), config.getUser(), config.getPassword(),
                msg -> System.err.println("  " + msg))) {
            StreamingNeo4jToRdf12Exporter.ExportStats stats = exporter.exportTo(outPath);
            System.err.println("Done. Reification blocks: " + stats.blocks);
        }
    }
}
