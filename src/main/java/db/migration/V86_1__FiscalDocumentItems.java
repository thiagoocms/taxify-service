package db.migration;

import db.migration.TableMigration;

import java.util.List;

import static db.migration.FiscalDocumentTables.DOCUMENTS;
import static db.migration.FiscalDocumentTables.ITEMS;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, 3.6): the items of the goods invoices (NF-e and NFC-e), empty -
 * V87 fills it. Same content as {@code fiscal_xml_document_items}, English names, rates widened to
 * {@code decimal(9,4)}. No auditing columns, as today: the item is part of the note. The covering index
 * {@code (document_id, cfop, total_amount)} serves the assessment, the stock credit, the foreign key and the detail.
 */
public class V86_1__FiscalDocumentItems extends TableMigration {

    public V86_1__FiscalDocumentItems() {
        super(ITEMS);
        lockWaitSeconds(CutoverDatabase.LOCK_WAIT_SECONDS);

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
        // FD-RN-57: the CFOP of the version in effect (the rectified XML's, else cfop) - the notes screen filters by it
        column("effective_cfop", "varchar(10)");
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
        index("idx_fdi_document_effective_cfop", "document_id", "effective_cfop");
        foreignKey("fk_fiscal_document_items_document", "document_id", DOCUMENTS);
    }
}
