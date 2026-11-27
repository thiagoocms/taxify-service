package br.com.cshub.taxify.enumerated;

/**
 * How a source delivered a fiscal document ({@code fiscal_document_sources.retrieval_mode}, spec 3.10). The
 * {@code Skip} of the SIEG period capture counts only {@code SIEG} + {@code PERIOD}: a note brought by NSU does not take a
 * position in the period list.
 */
public enum RetrievalMode {
    /** Count of the period plus pagination (SIEG today). */
    PERIOD,
    /** By NSU (SIEG NSU capture). */
    NSU,
    /** Cursor of the authority (SEFAZ DF-e, ADN). */
    CURSOR,
    /** Not retrieved: uploaded by hand. */
    NONE
}
