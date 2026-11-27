package db.fiscaldocuments;

import db.migration.TableMigration;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, B-FD-02): the five new tables, empty - V87 fills them.
 * <ul>
 *   <li>{@code fiscal_documents}: NF-e, NFC-e, NFS-e and CT-e in one {@code SINGLE_TABLE} hierarchy, discriminator
 *   {@code document_type} (FD-RN-01, 02), {@code ROW_FORMAT=DYNAMIC} (row size in 3.5);</li>
 *   <li>{@code fiscal_document_items} (3.6), {@code fiscal_document_events} (3.7), {@code fiscal_document_sources}
 *   (3.10, T4) and {@code capture_period_counts} (3.11, T2).</li>
 * </ul>
 * Each table is declared like any other {@link TableMigration} (create when missing, complete when present, keys only
 * when missing), so a run interrupted half-way just goes on. At the end every declared key, foreign key and the
 * {@code CHECK} must be there: {@code TableMigration} only warns when one can't be created, and these tables are the
 * base of the copy, so a missing one fails the migration here instead.
 *
 * <p>Ids are {@code varchar(36)} in the database default charset, like {@code companies.id} (the foreign keys need
 * the same charset). Constraint names are new: none collides with the renamed old tables' ones.
 */
public class V86__FiscalDocuments extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V86__FiscalDocuments.class);

    static final String DOCUMENTS = "fiscal_documents";
    static final String ITEMS = "fiscal_document_items";
    static final String EVENTS = "fiscal_document_events";
    static final String SOURCES = "fiscal_document_sources";
    static final String COUNTS = "capture_period_counts";
    static final List<String> TABLES = List.of(DOCUMENTS, ITEMS, EVENTS, SOURCES, COUNTS);

    static final String DOCUMENT_TYPE_CHECK = "chk_fiscal_documents_document_type";

    private record Expected(String table, List<String> indexes, List<String[]> foreignKeys) {
    }

    private static final List<Expected> EXPECTED = List.of(
            new Expected(DOCUMENTS, List.of("uk_fiscal_documents_company_key", "idx_fd_company_type_issue",
                    "idx_fd_company_type_direction_issue", "idx_fd_company_type_source_created", "idx_fd_purge",
                    "idx_fd_type_metadata_version"), List.<String[]>of(new String[]{"company_id", "companies"})),
            new Expected(ITEMS, List.of("idx_fdi_document_cfop_amount"), List.<String[]>of(new String[]{"document_id", DOCUMENTS})),
            new Expected(EVENTS, List.of("uk_fiscal_document_events", "idx_fde_document"),
                    List.of(new String[]{"company_id", "companies"}, new String[]{"document_id", DOCUMENTS})),
            new Expected(SOURCES, List.of("uk_fiscal_document_sources", "idx_fds_company_source_seen"),
                    List.<String[]>of(new String[]{"document_id", DOCUMENTS})),
            new Expected(COUNTS, List.of("uk_capture_period_counts", "idx_cpc_channel_period"),
                    List.<String[]>of(new String[]{"company_id", "companies"})));

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) {
        CutoverDatabase db = new CutoverDatabase(context);
        db.withShortLockWait("V86 create tables", () -> {
            for (TableMigration table : List.of(documents(), items(), events(), sources(), counts())) {
                table.migrate(context);
            }
        });
        verify(db);
    }

    private void verify(CutoverDatabase db) {
        List<String> missing = new ArrayList<>();
        for (Expected expected : EXPECTED) {
            if (!db.tableExists(expected.table())) {
                missing.add(expected.table());
                continue;
            }
            for (String index : expected.indexes()) {
                if (db.count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND index_name = ?", expected.table(), index) == 0) {
                    missing.add(expected.table() + "." + index);
                }
            }
            for (String[] fk : expected.foreignKeys()) {
                if (db.count("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND column_name = ? AND referenced_table_name = ?", expected.table(), fk[0], fk[1]) == 0) {
                    missing.add(expected.table() + "." + fk[0] + " -> " + fk[1]);
                }
            }
        }
        boolean keepsChecks = db.count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'information_schema'"
                + " AND table_name = 'CHECK_CONSTRAINTS'") > 0;
        if (keepsChecks && db.count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE()"
                + " AND table_name = ? AND constraint_name = ?", DOCUMENTS, DOCUMENT_TYPE_CHECK) == 0) {
            missing.add(DOCUMENTS + "." + DOCUMENT_TYPE_CHECK);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("V86 fiscal documents: missing after the run (see the warnings above): " + missing);
        }
        log.info("fiscal.documents.tables.ready tables={}", TABLES.size());
    }

    // ------------------------------------------------------------------ fiscal_documents (3.4)

    private static TableMigration documents() {
        return new TableMigration(DOCUMENTS) {
            {
                tableOptions("ROW_FORMAT=DYNAMIC");
                // common block (C)
                column("id", "varchar(36) not null");
                column("company_id", "varchar(36) not null");
                column("document_type", "varchar(10) not null");
                column("access_key", "varchar(80)");
                column("model", "varchar(2)");
                column("series", "varchar(5)");
                column("number", "varchar(20)");
                column("issue_date", "datetime(6)");
                column("operation_nature", "varchar(60)");
                column("direction", "varchar(10)");
                column("company_role", "varchar(12) not null");
                column("status", "varchar(12) not null");
                column("canceled_at", "datetime(6)");
                column("issuer_document", "varchar(14)");
                column("issuer_document_type", "varchar(10)");
                column("issuer_name", "varchar(200)");
                column("issuer_uf", "char(2)");
                column("issuer_city_code", "varchar(10)");
                column("recipient_document", "varchar(20)");
                column("recipient_document_type", "varchar(10)");
                column("recipient_name", "varchar(200)");
                column("recipient_uf", "char(2)");
                column("recipient_city_code", "varchar(10)");
                column("total_amount", "decimal(19,2)");
                column("ibs_cbs_base", "decimal(19,2)");
                column("ibs_state_amount", "decimal(19,2)");
                column("ibs_city_amount", "decimal(19,2)");
                column("ibs_amount", "decimal(19,2)");
                column("cbs_amount", "decimal(19,2)");
                column("withheld_pis_amount", "decimal(19,2)");
                column("withheld_cofins_amount", "decimal(19,2)");
                column("withheld_csll_amount", "decimal(19,2)");
                column("withheld_irrf_amount", "decimal(19,2)");
                column("withheld_inss_amount", "decimal(19,2)");
                column("source_system", "varchar(20) not null");
                column("xml_file_path", "varchar(1000)");
                column("xml_purged_at", "datetime(6)");
                column("metadata_version", "int not null");
                column("review_status", "varchar(12)");
                column("reviewed_at", "datetime(6)");
                column("reviewed_by", "varchar(50)");
                column("review_note", "varchar(500)");
                column("created_by", "varchar(50) not null");
                column("created_date", "datetime(6) not null");
                column("last_modified_by", "varchar(50)");
                column("last_modified_date", "datetime(6)");
                column("deleted", "bit not null");
                // goods invoices (G): NF-e and NFC-e
                column("rectified_file_path", "varchar(1000)");
                column("last_rectification_checked_at", "datetime(6)");
                for (String header : List.of("header_uf_code", "header_operation_type", "header_destination_type",
                        "header_environment", "header_purpose", "header_final_consumer", "header_presence_indicator",
                        "header_print_type", "header_emission_type", "header_check_digit", "header_emission_process")) {
                    column(header, "varchar(5)");
                }
                column("header_emission_process_version", "varchar(30)");
                column("header_exit_entry_at", "datetime(6)");
                column("header_taxable_event_city_code", "varchar(10)");
                column("issuer_trade_name", "varchar(200)");
                column("issuer_state_registration", "varchar(20)");
                column("issuer_st_state_registration", "varchar(20)");
                column("issuer_municipal_registration", "varchar(20)");
                column("issuer_cnae", "varchar(10)");
                column("issuer_tax_regime_code", "varchar(5)");
                column("recipient_state_registration", "varchar(20)");
                column("recipient_state_registration_indicator", "varchar(5)");
                column("recipient_email", "varchar(200)");
                for (String party : List.of("issuer", "recipient")) {
                    column(party + "_address_street", "varchar(200)");
                    column(party + "_address_number", "varchar(20)");
                    column(party + "_address_complement", "varchar(100)");
                    column(party + "_address_district", "varchar(100)");
                    column(party + "_address_city_name", "varchar(100)");
                    column(party + "_address_zip_code", "varchar(10)");
                    column(party + "_address_phone", "varchar(20)");
                    column(party + "_address_country_code", "varchar(10)");
                    column(party + "_address_country_name", "varchar(60)");
                }
                for (String total : List.of("icms_base", "icms_amount", "icms_relief_amount", "fcp_amount", "icms_st_base",
                        "icms_st_amount", "fcp_st_amount", "products_amount", "freight_amount", "insurance_amount",
                        "discount_amount", "import_tax_amount", "ipi_amount", "other_expenses_amount",
                        "approximate_taxes_amount", "pis_amount", "cofins_amount")) {
                    column(total, "decimal(19,2)");
                }
                // NFS-e (S); pis_amount and cofins_amount are shared with G
                column("nfse_layout", "varchar(10)");
                column("nfse_verification_code", "varchar(60)");
                column("nfse_competence", "date");
                column("nfse_incidence_city_code", "varchar(7)");
                column("nfse_service_code", "varchar(20)");
                column("nfse_municipal_tax_code", "varchar(30)");
                column("nfse_service_description", "TEXT");
                column("nfse_deductions_amount", "decimal(19,2)");
                column("iss_base", "decimal(19,2)");
                column("iss_rate", "decimal(9,4)");
                column("iss_amount", "decimal(19,2)");
                column("iss_withheld", "bit");
                column("withheld_iss_amount", "decimal(19,2)");
                column("nfse_pis_cofins_withheld", "bit");
                column("nfse_net_amount", "decimal(19,2)");
                // CT-e (T); icms_base and icms_amount are shared with G
                column("cte_type", "varchar(2)");
                column("cte_service_type", "varchar(2)");
                column("cte_modal", "varchar(2)");
                column("cte_cfop", "varchar(4)");
                for (String party : List.of("sender", "shipper", "receiver", "taker")) {
                    column(party + "_document", "varchar(14)");
                    column(party + "_document_type", "varchar(10)");
                    column(party + "_name", "varchar(200)");
                }
                column("sender_uf", "char(2)");
                column("taker_uf", "char(2)");
                column("cte_taker_role", "varchar(10)");
                column("cte_origin_city_code", "varchar(7)");
                column("cte_origin_uf", "char(2)");
                column("cte_destination_city_code", "varchar(7)");
                column("cte_destination_uf", "char(2)");
                column("cte_amount_receivable", "decimal(19,2)");
                column("cte_cargo_value", "decimal(19,2)");
                column("cte_icms_group", "varchar(12)");
                column("cte_icms_cst", "varchar(3)");
                column("cte_icms_rate", "decimal(9,4)");
                for (String role : List.of("issuer", "sender", "shipper", "receiver", "recipient", "taker")) {
                    column("cte_is_" + role, "bit");
                }

                check(DOCUMENT_TYPE_CHECK, "document_type IN ('NFE','NFCE','NFSE','CTE')");
                unique("uk_fiscal_documents_company_key", "company_id", "access_key");
                index("idx_fd_company_type_issue", "company_id", "document_type", "issue_date");
                index("idx_fd_company_type_direction_issue", "company_id", "document_type", "direction", "issue_date");
                index("idx_fd_company_type_source_created", "company_id", "document_type", "source_system", "created_date");
                index("idx_fd_purge", "xml_purged_at", "issue_date");
                index("idx_fd_type_metadata_version", "document_type", "metadata_version");
                foreignKey("fk_fiscal_documents_company", "company_id", "companies");
            }
        };
    }

    // ------------------------------------------------------------------ fiscal_document_items (3.6)

    private static TableMigration items() {
        return new TableMigration(ITEMS) {
            {
                tableOptions("ROW_FORMAT=DYNAMIC");
                column("id", "varchar(36) not null");
                column("document_id", "varchar(36) not null");
                column("item_number", "int");
                column("product_code", "varchar(60)");
                column("barcode", "varchar(20)");
                column("description", "varchar(500)");
                column("ncm", "varchar(10)");
                column("cest", "varchar(10)");
                column("benefit_code", "varchar(10)");
                column("cfop", "varchar(10)");
                column("commercial_unit", "varchar(10)");
                column("taxable_unit", "varchar(10)");
                column("commercial_quantity", "decimal(19,4)");
                column("commercial_unit_price", "decimal(19,4)");
                column("taxable_quantity", "decimal(19,4)");
                column("taxable_unit_price", "decimal(19,4)");
                column("total_amount", "decimal(19,2)");
                column("goods_origin", "varchar(1)");
                column("icms_cst", "varchar(5)");
                column("icms_csosn", "varchar(4)");
                column("icms_base_modality", "varchar(1)");
                column("icms_st_base_modality", "varchar(1)");
                for (String money : List.of("icms_base", "icms_amount", "icms_relief_amount", "icms_st_base", "icms_st_amount")) {
                    column(money, "decimal(19,2)");
                }
                for (String rate : List.of("icms_rate", "icms_base_reduction", "icms_st_mva", "icms_st_rate")) {
                    column(rate, "decimal(9,4)");
                }
                column("ipi_cst", "varchar(5)");
                column("pis_cst", "varchar(5)");
                column("cofins_cst", "varchar(5)");
                for (String money : List.of("ipi_base", "ipi_amount", "pis_base", "pis_amount", "cofins_base", "cofins_amount")) {
                    column(money, "decimal(19,2)");
                }
                for (String rate : List.of("ipi_rate", "pis_rate", "cofins_rate")) {
                    column(rate, "decimal(9,4)");
                }
                column("ibs_cbs_cst", "varchar(3)");
                column("ibs_cbs_class_code", "varchar(6)");
                for (String money : List.of("ibs_cbs_base", "ibs_state_amount", "ibs_city_amount", "ibs_amount", "cbs_amount")) {
                    column(money, "decimal(19,2)");
                }
                for (String rate : List.of("ibs_state_rate", "ibs_state_rate_reduction", "ibs_city_rate",
                        "ibs_city_rate_reduction", "cbs_rate", "cbs_rate_reduction")) {
                    column(rate, "decimal(9,4)");
                }
                column("ibs_cbs_deferral_or_refund", "bit");
                column("simples_credit_rate", "decimal(9,4)");
                column("simples_credit_amount", "decimal(19,2)");

                index("idx_fdi_document_cfop_amount", "document_id", "cfop", "total_amount");
                foreignKey("fk_fiscal_document_items_document", "document_id", DOCUMENTS);
            }
        };
    }

    // ------------------------------------------------------------------ fiscal_document_events (3.7)

    private static TableMigration events() {
        return new TableMigration(EVENTS) {
            {
                column("id", "varchar(36) not null");
                column("company_id", "varchar(36) not null");
                column("document_id", "varchar(36)");
                column("access_key", "varchar(80) not null");
                column("event_type", "varchar(6) not null");
                column("sequence_number", "int not null");
                column("description", "varchar(120)");
                column("event_at", "datetime(6)");
                column("protocol", "varchar(30)");
                column("event_text", "TEXT");
                column("registered", "bit not null");
                column("created_by", "varchar(50) not null");
                column("created_date", "datetime(6) not null");
                column("last_modified_by", "varchar(50)");
                column("last_modified_date", "datetime(6)");
                column("deleted", "bit not null");

                unique("uk_fiscal_document_events", "company_id", "access_key", "event_type", "sequence_number");
                index("idx_fde_document", "document_id");
                foreignKey("fk_fiscal_document_events_company", "company_id", "companies");
                foreignKey("fk_fiscal_document_events_document", "document_id", DOCUMENTS);
            }
        };
    }

    // ------------------------------------------------------------------ fiscal_document_sources (3.10, T4)

    private static TableMigration sources() {
        return new TableMigration(SOURCES) {
            {
                column("id", "varchar(36) not null");
                column("document_id", "varchar(36) not null");
                column("company_id", "varchar(36) not null");
                column("source", "varchar(30) not null");
                column("first_seen_at", "datetime(6) not null");
                column("last_seen_at", "datetime(6) not null");

                unique("uk_fiscal_document_sources", "document_id", "source");
                index("idx_fds_company_source_seen", "company_id", "source", "first_seen_at");
                foreignKey("fk_fiscal_document_sources_document", "document_id", DOCUMENTS);
            }
        };
    }

    // ------------------------------------------------------------------ capture_period_counts (3.11, T2)

    private static TableMigration counts() {
        return new TableMigration(COUNTS) {
            {
                column("id", "varchar(36) not null");
                column("company_id", "varchar(36) not null");
                column("channel", "varchar(30) not null");
                column("document_type", "varchar(10) not null");
                column("side", "varchar(10) not null");
                column("period_month", "char(7) not null");
                column("window_start", "date");
                column("window_end", "date");
                column("provider_total", "int");
                column("provider_counted_at", "datetime(6)");
                column("imported_total", "int not null default 0");
                column("stored_any_source_total", "int");
                column("known_gap", "int");
                column("imported_counted_at", "datetime(6)");
                column("closed_at", "datetime(6)");
                column("created_by", "varchar(50) not null");
                column("created_date", "datetime(6) not null");
                column("last_modified_by", "varchar(50)");
                column("last_modified_date", "datetime(6)");
                column("deleted", "bit not null");

                unique("uk_capture_period_counts", "company_id", "channel", "document_type", "side", "period_month");
                index("idx_cpc_channel_period", "channel", "document_type", "period_month");
                foreignKey("fk_capture_period_counts_company", "company_id", "companies");
            }
        };
    }
}
