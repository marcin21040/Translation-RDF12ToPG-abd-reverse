package org.example.rdf2pg;

import org.neo4j.driver.Session;

/**
 * Creates standard indexes without blocking import if they are still populating.
 */
public final class Neo4jIndexHelper {

    private Neo4jIndexHelper() {}

    public static void ensureResourceIndexes(Session session) {
        session.executeWrite(tx -> {
            tx.run("CREATE INDEX resource_id IF NOT EXISTS FOR (n:Resource) ON (n.id)");
            tx.run("CREATE INDEX literal_value IF NOT EXISTS FOR (n:Literal) ON (n.value)");
            return null;
        });
    }

    /**
     * Best-effort wait; never throws — import can proceed without fully online indexes.
     */
    public static void awaitIndexesBestEffort(Session session, java.util.function.Consumer<String> log) {
        try {
            session.executeWrite(tx -> {
                tx.run("CALL db.awaitIndexes(300)");
                return null;
            });
        } catch (Exception e) {
            if (log != null) {
                log.accept("Indexes still building — continuing anyway (" + e.getMessage() + ")");
            }
        }
    }
}
