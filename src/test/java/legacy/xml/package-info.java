/**
 * The old fiscal document model ({@code fiscal_xml_documents}, {@code fiscal_xml_document_items},
 * {@code service_invoices}, {@code fiscal_xml_events} and the counters of {@code fiscal_xml_sync_control}), removed from
 * {@code src/main} by the fiscal documents remodel (spec 2026-11-27, B-FD-05). Kept here only as the input of the
 * reference translator ({@code LegacyFiscalDocumentTranslator}) and of the FD-RN-55 comparison with V87. Outside
 * {@code br.com.cshub.taxify} on purpose: the application's entity scan never sees these classes; the entity names
 * carry the prefix {@code Legacy} so a standalone Hibernate can load them next to the new model.
 */
package legacy.xml;
