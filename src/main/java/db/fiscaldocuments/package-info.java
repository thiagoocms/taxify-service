/**
 * Data migration of the fiscal documents remodel (spec {@code docs/specs/2026-11-27-remodelagem-documentos-fiscais.md},
 * B-FD-02 and B-FD-03): {@code V86} to {@code V86_4} create {@code fiscal_documents}, {@code fiscal_document_items},
 * {@code fiscal_document_events}, {@code fiscal_document_sources} and {@code capture_period_counts}; {@code V87} renames
 * the four old tables to {@code legacy_*} and copies them (same ids, resumable); {@code V88} compares old and new
 * with zero tolerance; {@code V89} drops the legacy tables and the counter columns of {@code fiscal_xml_sync_control}.
 *
 * <p><b>Not loaded by the application yet.</b> This is a Flyway location of its own ({@code classpath:db/fiscaldocuments})
 * that {@code spring.flyway.locations} does not list: V87/V89 remove tables the current entities still map, and
 * {@code ddl-auto: validate} would stop the application. Only the migration tests run it, with an explicit Flyway.
 *
 * <p><b>At integration</b> (the release that swaps the entities, spec 6.4): move these classes to {@code db.migration}
 * in the same commit as the new entities and the removal of {@code FiscalXmlDocument}, {@code ServiceInvoice}...;
 * keep the versions (86, 86.1 to 86.4, 87, 88, 89 - after V85 of {@code fix/sieg-call-savings}); add
 * {@code FD_BACKUP_SHA256} to the compose files ({@code ${FD_BACKUP_SHA256:-}}: empty keeps the legacy tables, V89
 * stops) and map it to {@code spring.flyway.placeholders.fd_backup_sha256} or leave the variable; point the tests at
 * {@code classpath:db/migration} only; and keep {@code LegacyFiscalDocumentTranslator} and {@code
 * CopyMappings} saying the same thing (the domain test of the translator and the migration test of
 * the copy cover the same cases).
 */
package db.fiscaldocuments;
