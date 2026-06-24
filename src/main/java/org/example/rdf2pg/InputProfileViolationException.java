package org.example.rdf2pg;

/**
 * RDF input does not satisfy the accepted translation profile (reifier / triple-term constraints).
 */
public class InputProfileViolationException extends RuntimeException {

    public InputProfileViolationException(String message) {
        super(message);
    }
}
