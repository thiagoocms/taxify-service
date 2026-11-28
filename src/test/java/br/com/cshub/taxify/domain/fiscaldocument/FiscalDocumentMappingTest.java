package br.com.cshub.taxify.domain.fiscaldocument;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping of the new model, checked without a database: Hibernate builds and validates the metadata of the whole
 * model with the MySQL dialect and no JDBC access, and the tables,
 * columns, types, keys and indexes are compared with spec 3.4 to 3.11.
 */
class FiscalDocumentMappingTest {

    private static final String BASE_PACKAGE = "br.com.cshub.taxify";

    private static StandardServiceRegistry registry;
    private static Metadata metadata;

    @BeforeAll
    static void buildMetadata() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        scan().getManagedClassNames().forEach(sources::addAnnotatedClassName);
        metadata = sources.buildMetadata();
        ((MetadataImplementor) metadata).validate();
    }

    @AfterAll
    static void close() {
        StandardServiceRegistryBuilder.destroy(registry);
    }

    private static PersistenceManagedTypes scan() {
        return new PersistenceManagedTypesScanner(new DefaultResourceLoader()).scan(BASE_PACKAGE);
    }

    private static Table table(String name) {
        return metadata.getDatabase().getDefaultNamespace().getTables().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("table not mapped: " + name));
    }

    private static Map<String, Column> columns(String table) {
        return table(table).getColumns().stream().collect(Collectors.toMap(Column::getName, c -> c));
    }

    private static Set<String> names(String... values) {
        return Arrays.stream(values).collect(Collectors.toSet());
    }

    private static String sqlType(String table, String column) {
        return columns(table).get(column).getSqlType(metadata).toLowerCase();
    }

    private static final Set<String> COMMON = names("id", "company_id", "document_type", "access_key", "model", "series",
            "number", "issue_date", "operation_nature", "direction", "company_role", "status", "canceled_at",
            "issuer_document", "issuer_document_type", "issuer_name", "issuer_uf", "issuer_city_code",
            "recipient_document", "recipient_document_type", "recipient_name", "recipient_uf", "recipient_city_code",
            "total_amount", "ibs_cbs_base", "ibs_state_amount", "ibs_city_amount", "ibs_amount", "cbs_amount",
            "withheld_pis_amount", "withheld_cofins_amount", "withheld_csll_amount", "withheld_irrf_amount",
            "withheld_inss_amount", "source_system", "xml_file_path", "xml_purged_at", "metadata_version",
            "review_status", "reviewed_at", "reviewed_by", "review_note",
            "created_by", "created_date", "last_modified_by", "last_modified_date", "deleted");

    private static final Set<String> GOODS = names("rectified_file_path", "last_rectification_checked_at",
            "header_uf_code", "header_operation_type", "header_destination_type", "header_environment", "header_purpose",
            "header_final_consumer", "header_presence_indicator", "header_print_type", "header_emission_type",
            "header_check_digit", "header_emission_process", "header_emission_process_version", "header_exit_entry_at",
            "header_taxable_event_city_code", "issuer_trade_name", "issuer_state_registration",
            "issuer_st_state_registration", "issuer_municipal_registration", "issuer_cnae", "issuer_tax_regime_code",
            "recipient_state_registration", "recipient_state_registration_indicator", "recipient_email",
            "issuer_address_street", "issuer_address_number", "issuer_address_complement", "issuer_address_district",
            "issuer_address_city_name", "issuer_address_zip_code", "issuer_address_phone", "issuer_address_country_code",
            "issuer_address_country_name", "recipient_address_street", "recipient_address_number",
            "recipient_address_complement", "recipient_address_district", "recipient_address_city_name",
            "recipient_address_zip_code", "recipient_address_phone", "recipient_address_country_code",
            "recipient_address_country_name", "icms_base", "icms_amount", "icms_relief_amount", "fcp_amount",
            "icms_st_base", "icms_st_amount", "fcp_st_amount", "products_amount", "freight_amount", "insurance_amount",
            "discount_amount", "import_tax_amount", "ipi_amount", "other_expenses_amount", "approximate_taxes_amount",
            "pis_amount", "cofins_amount");

    private static final Set<String> SERVICE = names("nfse_layout", "nfse_verification_code", "nfse_competence",
            "nfse_incidence_city_code", "nfse_service_code", "nfse_municipal_tax_code", "nfse_service_description",
            "nfse_deductions_amount", "iss_base", "iss_amount", "withheld_iss_amount", "iss_rate", "iss_withheld",
            "pis_amount", "cofins_amount", "nfse_pis_cofins_withheld", "nfse_net_amount");

    private static final Set<String> TRANSPORT = names("cte_type", "cte_service_type", "cte_modal", "cte_cfop",
            "sender_document", "shipper_document", "receiver_document", "taker_document", "sender_document_type",
            "shipper_document_type", "receiver_document_type", "taker_document_type", "sender_name", "shipper_name",
            "receiver_name", "taker_name", "sender_uf", "taker_uf", "cte_taker_role", "cte_origin_city_code",
            "cte_destination_city_code", "cte_origin_uf", "cte_destination_uf", "cte_amount_receivable",
            "cte_cargo_value", "cte_icms_group", "cte_icms_cst", "cte_icms_rate", "icms_base", "icms_amount",
            "cte_is_issuer", "cte_is_sender", "cte_is_shipper", "cte_is_receiver", "cte_is_recipient", "cte_is_taker");

    @Test
    void fiscalDocumentsHasExactlyTheColumnsOfSection34() {
        Set<String> expected = Stream.of(COMMON, GOODS, SERVICE, TRANSPORT).flatMap(Set::stream).collect(Collectors.toSet());
        assertThat(columns("fiscal_documents").keySet()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void discriminatorAndSubtypes() {
        assertThat(metadata.getEntityBinding(Nfe.class.getName()).getDiscriminatorValue()).isEqualTo("NFE");
        assertThat(metadata.getEntityBinding(Nfce.class.getName()).getDiscriminatorValue()).isEqualTo("NFCE");
        assertThat(metadata.getEntityBinding(Nfse.class.getName()).getDiscriminatorValue()).isEqualTo("NFSE");
        assertThat(metadata.getEntityBinding(Cte.class.getName()).getDiscriminatorValue()).isEqualTo("CTE");
        assertThat(metadata.getEntityBinding(FiscalDocument.class.getName()).getDiscriminator().getColumns())
                .extracting(Column::getName).containsExactly("document_type");
        assertThat(metadata.getEntityBinding(GoodsInvoice.class.getName()).isAbstract()).isTrue();
        assertThat(metadata.getEntityBinding(Nfe.class.getName()).getTable().getName()).isEqualTo("fiscal_documents");
        assertThat(metadata.getEntityBinding(Cte.class.getName()).getTable().getName()).isEqualTo("fiscal_documents");
    }

    @Test
    void typesAndPrecisionFollowTheSpec() {
        assertThat(sqlType("fiscal_documents", "issuer_uf")).isEqualTo("char(2)");
        assertThat(sqlType("fiscal_documents", "cte_origin_uf")).isEqualTo("char(2)");
        assertThat(sqlType("fiscal_documents", "nfse_service_description")).isEqualTo("text");
        assertThat(sqlType("fiscal_document_events", "event_text")).isEqualTo("text");
        assertThat(sqlType("capture_period_counts", "period_month")).isEqualTo("char(7)");
        Map<String, Column> columns = columns("fiscal_documents");
        assertThat(columns.get("access_key").getLength()).isEqualTo(80);
        assertThat(columns.get("issuer_document").getLength()).isEqualTo(14);
        assertThat(columns.get("recipient_document").getLength()).isEqualTo(20);
        assertThat(columns.get("document_type").getLength()).isEqualTo(10);
        assertThat(columns.get("source_system").getLength()).isEqualTo(30);
        assertThat(columns.get("total_amount").getPrecision()).isEqualTo(19);
        assertThat(columns.get("total_amount").getScale()).isEqualTo(2);
        assertThat(columns.get("iss_rate").getPrecision()).isEqualTo(9);
        assertThat(columns.get("iss_rate").getScale()).isEqualTo(4);
        assertThat(columns.get("cte_icms_rate").getScale()).isEqualTo(4);
        Map<String, Column> items = columns("fiscal_document_items");
        assertThat(items.get("icms_rate").getPrecision()).isEqualTo(9);
        assertThat(items.get("commercial_quantity").getScale()).isEqualTo(4);
        assertThat(items.get("simples_credit_amount").getScale()).isEqualTo(2);
    }

    @Test
    void onlyTheCommonMandatoryColumnsAreNotNull() {
        List<String> notNull = columns("fiscal_documents").values().stream()
                .filter(c -> !c.isNullable()).map(Column::getName).toList();
        assertThat(notNull).containsExactlyInAnyOrder("id", "company_id", "document_type", "company_role", "status",
                "source_system", "metadata_version", "created_by", "created_date", "deleted");
    }

    @Test
    void itemsEventsSourcesAndCountsHaveTheColumnsOfTheSpec() {
        assertThat(columns("fiscal_document_items").keySet()).containsExactlyInAnyOrder("id", "document_id",
                "item_number", "product_code", "barcode", "description", "ncm", "cest", "benefit_code", "cfop", "effective_cfop",
                "commercial_unit", "taxable_unit", "commercial_quantity", "commercial_unit_price", "taxable_quantity",
                "taxable_unit_price", "total_amount", "goods_origin", "icms_cst", "icms_csosn", "icms_base_modality",
                "icms_st_base_modality", "icms_base", "icms_amount", "icms_relief_amount", "icms_st_base", "icms_st_amount",
                "icms_rate", "icms_base_reduction", "icms_st_mva", "icms_st_rate", "ipi_cst", "pis_cst", "cofins_cst",
                "ipi_base", "ipi_amount", "pis_base", "pis_amount", "cofins_base", "cofins_amount", "ipi_rate", "pis_rate",
                "cofins_rate", "ibs_cbs_cst", "ibs_cbs_class_code", "ibs_cbs_base", "ibs_state_amount", "ibs_city_amount",
                "ibs_amount", "cbs_amount", "ibs_state_rate", "ibs_state_rate_reduction", "ibs_city_rate",
                "ibs_city_rate_reduction", "cbs_rate", "cbs_rate_reduction", "ibs_cbs_deferral_or_refund",
                "simples_credit_rate", "simples_credit_amount");
        assertThat(columns("fiscal_document_events").keySet()).containsExactlyInAnyOrder("id", "company_id",
                "document_id", "access_key", "event_type", "sequence_number", "description", "event_at", "protocol",
                "event_text", "registered", "created_by", "created_date", "last_modified_by", "last_modified_date",
                "deleted");
        assertThat(columns("fiscal_document_sources").keySet()).containsExactlyInAnyOrder("id", "document_id",
                "company_id", "source", "retrieval_mode", "first_seen_at", "last_seen_at");
        assertThat(columns("capture_period_counts").keySet()).containsExactlyInAnyOrder("id", "company_id", "channel",
                "document_type", "side", "period_month", "window_start", "window_end", "provider_total",
                "provider_counted_at", "imported_total", "stored_any_source_total", "known_gap", "imported_counted_at",
                "closed_at", "created_by", "created_date", "last_modified_by", "last_modified_date", "deleted");
    }

    @Test
    void uniqueKeysIndexesAndForeignKeysOfSection38() {
        assertThat(uniqueKeys("fiscal_documents")).containsEntry("uk_fiscal_documents_company_key", List.of("company_id", "access_key"));
        assertThat(indexes("fiscal_documents")).containsOnly(
                Map.entry("idx_fd_company_type_issue", List.of("company_id", "document_type", "issue_date")),
                Map.entry("idx_fd_company_type_direction_issue", List.of("company_id", "document_type", "direction", "issue_date")),
                Map.entry("idx_fd_company_type_source_created", List.of("company_id", "document_type", "source_system", "created_date")),
                Map.entry("idx_fd_purge", List.of("xml_purged_at", "issue_date")),
                Map.entry("idx_fd_type_metadata_version", List.of("document_type", "metadata_version")));
        assertThat(indexes("fiscal_document_items"))
                .containsOnly(Map.entry("idx_fdi_document_cfop_amount", List.of("document_id", "cfop", "total_amount")),
                        Map.entry("idx_fdi_document_effective_cfop", List.of("document_id", "effective_cfop")));
        assertThat(uniqueKeys("fiscal_document_events")).containsEntry("uk_fiscal_document_events",
                List.of("company_id", "access_key", "event_type", "sequence_number"));
        assertThat(indexes("fiscal_document_events")).containsOnly(Map.entry("idx_fde_document", List.of("document_id")));
        assertThat(uniqueKeys("fiscal_document_sources")).containsEntry("uk_fiscal_document_sources", List.of("document_id", "source", "retrieval_mode"));
        assertThat(indexes("fiscal_document_sources"))
                .containsOnly(Map.entry("idx_fds_company_source_seen", List.of("company_id", "source", "first_seen_at")));
        assertThat(uniqueKeys("capture_period_counts")).containsEntry("uk_capture_period_counts",
                List.of("company_id", "channel", "document_type", "side", "period_month"));
        assertThat(indexes("capture_period_counts"))
                .containsOnly(Map.entry("idx_cpc_channel_period", List.of("channel", "document_type", "period_month")));
        assertThat(foreignKeys("fiscal_documents")).containsExactly("fk_fiscal_documents_company");
        assertThat(foreignKeys("fiscal_document_items")).containsExactly("fk_fiscal_document_items_document");
        assertThat(foreignKeys("fiscal_document_events"))
                .containsExactlyInAnyOrder("fk_fiscal_document_events_company", "fk_fiscal_document_events_document");
        assertThat(foreignKeys("fiscal_document_sources")).containsExactly("fk_fiscal_document_sources_document");
        assertThat(foreignKeys("capture_period_counts")).containsExactly("fk_capture_period_counts_company");
    }

    /** The staging filter is gone (spec 3.12 D-01): the scan of the application lists every new entity and converter. */
    @Test
    void everyFiscalDocumentEntityIsScanned() {
        List<String> all = scan().getManagedClassNames();
        assertThat(all).contains(FiscalDocument.class.getName(), GoodsInvoice.class.getName(), Nfe.class.getName(),
                Nfce.class.getName(), Nfse.class.getName(), Cte.class.getName(), FiscalDocumentItem.class.getName(),
                FiscalDocumentEvent.class.getName(), FiscalDocumentSource.class.getName(), CapturePeriodCount.class.getName(),
                DocumentSourceKeyConverter.class.getName());
        assertThat(all).noneMatch(name -> name.startsWith("br.com.cshub.taxify.domain.xml.FiscalXmlDocument")
                || name.equals("br.com.cshub.taxify.domain.xml.ServiceInvoice")
                || name.equals("br.com.cshub.taxify.domain.xml.FiscalXmlEvent"));
    }

    private static Map<String, List<String>> uniqueKeys(String table) {
        return table(table).getUniqueKeys().values().stream().collect(Collectors.toMap(
                uk -> uk.getName(), uk -> uk.getColumns().stream().map(Column::getName).toList()));
    }

    private static Map<String, List<String>> indexes(String table) {
        return table(table).getIndexes().values().stream().collect(Collectors.toMap(
                index -> index.getName(), index -> index.getColumns().stream().map(Column::getName).toList()));
    }

    private static List<String> foreignKeys(String table) {
        return table(table).getForeignKeyCollection().stream().map(ForeignKey::getName).toList();
    }
}
