package br.com.cshub.taxify.enumerated;

/**
 * How strictly a fiscal document is checked (spec 3.3): a new document (capture, upload, parser) gets every rule; an
 * already stored one - including the rows copied from the old tables, which may carry nulls the new model no longer
 * accepts - only the rules marked "always".
 */
public enum InvariantScope {
    CREATE,
    UPDATE;

    public boolean isCreate() {
        return this == CREATE;
    }
}
