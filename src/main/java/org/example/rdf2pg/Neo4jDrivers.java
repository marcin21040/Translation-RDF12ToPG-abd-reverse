package org.example.rdf2pg;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.concurrent.TimeUnit;

/** Shared Neo4j driver factory with timeouts suited to large experiment graphs. */
public final class Neo4jDrivers {

    private Neo4jDrivers() {}

    public static Driver create(String uri, String user, String password) {
        Config config = Config.builder()
                .withMaxConnectionLifetime(30, TimeUnit.MINUTES)
                .withConnectionAcquisitionTimeout(2, TimeUnit.MINUTES)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
    }
}
