package db.fiscaldocuments;

import db.migration.TableMigration;

import java.util.List;

import static db.fiscaldocuments.FiscalDocumentTables.DOCUMENTS;
import static db.fiscaldocuments.FiscalDocumentTables.DOCUMENT_TYPE_CHECK;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, B-FD-02, 3.4): {@code fiscal_documents}, empty - V87 fills it.
 * NF-e, NFC-e, NFS-e and CT-e in one {@code SINGLE_TABLE} hierarchy with the discriminator {@code document_type} and
 * its {@code CHECK} (FD-RN-01, 02); {@code ROW_FORMAT=DYNAMIC} (row size in 3.5: about 26 KB declared, 40% of the
 * limit; the NFS-e description is the only {@code TEXT}). Blocks: common (C), goods invoices (G), NFS-e (S) and CT-e
 * (T); {@code icms_base}/{@code icms_amount} are shared by G and T, {@code pis_amount}/{@code cofins_amount} by G and S.
 *
 * <p>Ids are {@code varchar(36)} in the database default charset, like {@code companies.id} (the foreign key needs the
 * same charset). Constraint names are new: none collides with the renamed old tables' ones. The other four tables are
 * V86_1 to V86_4, one per migration like the rest of the schema.
 */
public class V86__FiscalDocuments extends TableMigration {

    public V86__FiscalDocuments() {
        super(DOCUMENTS);
        lockWaitSeconds(CutoverDatabase.LOCK_WAIT_SECONDS);

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
        column("source_system", "varchar(30) not null");
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
}
