package org.example.rdf2pg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Konfiguracja połączenia Neo4j: zmienne środowiskowe mają pierwszeństwo, potem neo4j.properties.
 * <ul>
 *   <li>neo4j.uri (NEO4J_URI) – domyślnie bolt://localhost:7687</li>
 *   <li>neo4j.user (NEO4J_USER) – domyślnie neo4j</li>
 *   <li>neo4j.password (NEO4J_PASSWORD) – wymagane</li>
 * </ul>
 */
public class Neo4jConfig {

    public static final String DEFAULT_URI = "bolt://localhost:7687";
    public static final String DEFAULT_USER = "neo4j";

    private final String uri;
    private final String user;
    private final String password;

    public Neo4jConfig(String uri, String user, String password) {
        this.uri = uri != null && !uri.isBlank() ? uri : DEFAULT_URI;
        this.user = user != null && !user.isBlank() ? user : DEFAULT_USER;
        this.password = password;
    }

    public String getUri() {
        return uri;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    /**
     * Ładuje konfigurację: najpierw zmienne środowiskowe, potem neo4j.properties.
     */
    public static Neo4jConfig load() {
        Properties p = new Properties();
        Path configPath = Paths.get("neo4j.properties");
        if (Files.isRegularFile(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("Uwaga: nie można wczytać neo4j.properties: " + e.getMessage());
            }
        }
        String uri = firstNonBlank(
                System.getenv("NEO4J_URI"),
                p.getProperty("neo4j.uri"));
        String user = firstNonBlank(
                System.getenv("NEO4J_USER"),
                p.getProperty("neo4j.user"));
        String password = firstNonBlank(
                System.getenv("NEO4J_PASSWORD"),
                p.getProperty("neo4j.password"));
        return new Neo4jConfig(uri, user, password);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
