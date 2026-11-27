package db.fiscaldocuments;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static db.fiscaldocuments.LegacyFixtures.E1;
import static db.fiscaldocuments.LegacyFixtures.E1_CNPJ;
import static db.fiscaldocuments.LegacyFixtures.E2;
import static db.fiscaldocuments.LegacyFixtures.E3;
import static db.fiscaldocuments.LegacyFixtures.SUPPLIER_CNPJ;
import static db.fiscaldocuments.LegacyFixtures.SUPPLIER_CPF;
import static db.fiscaldocuments.LegacyFixtures.doc;
import static db.fiscaldocuments.LegacyFixtures.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V86 to V89 of the fiscal documents remodel (spec 2026-11-27) on a disposable database of the local MySQL: the
 * schema of the master (Flyway up to {@value #BEFORE}) with fictitious data covering every conversion, then the four
 * migrations, run by a Flyway pointed at both locations ({@code db/migration} and {@code db/fiscaldocuments}, which
 * the application doesn't load until the new entities land).
 *
 * <p>Scratch database {@value #SCRATCH}, created and dropped by the test; only with DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
class FiscalDocumentsMigrationsTest {

    static final String SCRATCH = "taxify_fdmig";
    static final String BEFORE = "84";
    static final String[] LOCATIONS = {"classpath:db/migration", "classpath:db/fiscaldocuments"};

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> migrationLoggers = List.of(V86__FiscalDocuments.class, V87__FiscalDocumentsCutover.class,
            V88__FiscalDocumentsParity.class, V89__DropLegacyFiscalDocuments.class).stream()
            .map(type -> (Logger) LoggerFactory.getLogger(type)).toList();

    static String url(String database) {
        return "jdbc:mysql://localhost:" + System.getenv().getOrDefault("DB_PORT", "3306") + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
    }

    static JdbcTemplate jdbc(String database) {
        String target = url(database);
        assertThat(target).startsWith("jdbc:mysql://localhost:");
        return new JdbcTemplate(new DriverManagerDataSource(target, System.getenv().getOrDefault("DB_USER", "root"),
                System.getenv().getOrDefault("DB_PASSWORD", "root")));
    }

    static Flyway flyway(String target, String... locations) {
        return Flyway.configure().dataSource(url(SCRATCH), System.getenv().getOrDefault("DB_USER", "root"),
                        System.getenv().getOrDefault("DB_PASSWORD", "root"))
                .locations(locations.length == 0 ? LOCATIONS : locations)
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    @BeforeEach
    void createScratchDatabase() {
        JdbcTemplate admin = jdbc("");
        admin.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        admin.execute("CREATE DATABASE " + SCRATCH + " CHARACTER SET utf8mb4");
        logs.start();
        migrationLoggers.forEach(logger -> logger.addAppender(logs));
    }

    @AfterEach
    void dropScratchDatabase() {
        migrationLoggers.forEach(logger -> logger.detachAppender(logs));
        jdbc("").execute("DROP DATABASE IF EXISTS " + SCRATCH);
    }

    private boolean logged(String prefix) {
        return logs.list.stream().anyMatch(event -> event.getFormattedMessage().startsWith(prefix));
    }

    private String log(String prefix) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefix)).reduce((first, last) -> last).orElseThrow();
    }

    static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, SCRATCH, table) > 0;
    }

    static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?"
                + " ORDER BY ordinal_position", String.class, SCRATCH, table);
    }

    private static Map<String, Object> document(JdbcTemplate jdbc, String id) {
        return jdbc.queryForMap("SELECT * FROM fiscal_documents WHERE id = ?", id);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static boolean historySuccess(JdbcTemplate jdbc, String version) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = 1",
                Integer.class, version) > 0;
    }

    // ------------------------------------------------------------------ CA-FD-02, 23; CT-FD-01..05, 11, 15..19, 27, 29, 31, 35, 36

    @Test
    void copiesEveryValueWithTheSameIdsAndDropsTheLegacy() {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        LegacyFixtures.scenario(jdbc);
        LegacyFixtures.addV85Columns(jdbc);
        LegacyFixtures.syncControls(jdbc);
        List<Map<String, Object>> pendenciesBefore = jdbc.queryForList("SELECT * FROM pendencies");
        List<Map<String, Object>> reviewsBefore = jdbc.queryForList("SELECT * FROM retention_reviews");

        flyway("88").migrate();

        assertThat(log("fiscal.documents.cutover.parity.ok")).contains("goods=").contains("capture-counts=");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents", Integer.class)).isEqualTo(13 + 5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_fiscal_xml_documents", Integer.class)).isEqualTo(13);

        // K1: every column of the common and goods blocks
        Map<String, Object> k1 = document(jdbc, doc("k1"));
        assertThat(k1).containsEntry("company_id", E1).containsEntry("document_type", "NFE").containsEntry("access_key", key(1))
                .containsEntry("model", "55").containsEntry("series", "1").containsEntry("number", "101")
                .containsEntry("issue_date", LocalDateTime.of(2026, 10, 5, 10, 0)).containsEntry("operation_nature", "Compra")
                .containsEntry("direction", "INBOUND").containsEntry("company_role", "RECIPIENT").containsEntry("status", "AUTHORIZED")
                .containsEntry("canceled_at", null).containsEntry("issuer_document", SUPPLIER_CNPJ)
                .containsEntry("issuer_document_type", "CNPJ").containsEntry("issuer_name", "Fornecedor Exemplo")
                .containsEntry("issuer_uf", "PE").containsEntry("issuer_city_code", "2611606")
                .containsEntry("recipient_document", E1_CNPJ).containsEntry("recipient_document_type", "CNPJ")
                .containsEntry("recipient_name", "Empresa 1").containsEntry("recipient_uf", "PE")
                .containsEntry("total_amount", money("1000.00")).containsEntry("ibs_cbs_base", money("1000.00"))
                .containsEntry("ibs_state_amount", money("1.00")).containsEntry("ibs_city_amount", money("0.00"))
                .containsEntry("ibs_amount", money("1.00")).containsEntry("cbs_amount", money("9.00"))
                .containsEntry("withheld_pis_amount", money("6.50")).containsEntry("withheld_cofins_amount", money("30.00"))
                .containsEntry("withheld_csll_amount", money("10.00")).containsEntry("withheld_irrf_amount", money("15.00"))
                .containsEntry("withheld_inss_amount", money("11.00")).containsEntry("source_system", "SIEG")
                .containsEntry("xml_file_path", "xml/" + doc("k1") + ".xml").containsEntry("xml_purged_at", null)
                .containsEntry("metadata_version", 6).containsEntry("review_status", null)
                .containsEntry("created_by", "qa@exemplo.test").containsEntry("created_date", LocalDateTime.of(2026, 11, 2, 8, 0))
                .containsEntry("last_modified_by", "fiscal1@exemplo.test")
                .containsEntry("last_modified_date", LocalDateTime.of(2026, 11, 6, 7, 30)).containsEntry("deleted", false)
                .containsEntry("rectified_file_path", "rect/k1.xml")
                .containsEntry("last_rectification_checked_at", LocalDateTime.of(2026, 11, 6, 7, 0))
                .containsEntry("header_uf_code", "26").containsEntry("header_operation_type", "1")
                .containsEntry("header_destination_type", "1").containsEntry("header_environment", "1")
                .containsEntry("header_purpose", "1").containsEntry("header_final_consumer", "0")
                .containsEntry("header_presence_indicator", "9").containsEntry("header_print_type", "1")
                .containsEntry("header_emission_type", "1").containsEntry("header_check_digit", "7")
                .containsEntry("header_emission_process", "0").containsEntry("header_emission_process_version", "Emissor 4.0")
                .containsEntry("header_exit_entry_at", LocalDateTime.of(2026, 10, 6, 9, 0))
                .containsEntry("header_taxable_event_city_code", "2611606").containsEntry("issuer_trade_name", "Fornecedor")
                .containsEntry("issuer_state_registration", "123456789").containsEntry("issuer_st_state_registration", "987")
                .containsEntry("issuer_municipal_registration", "555").containsEntry("issuer_cnae", "4711302")
                .containsEntry("issuer_tax_regime_code", "3").containsEntry("recipient_state_registration", "111")
                .containsEntry("recipient_state_registration_indicator", "1").containsEntry("recipient_email", "nfe@exemplo.test")
                .containsEntry("issuer_address_street", "Rua A").containsEntry("issuer_address_number", "10")
                .containsEntry("issuer_address_complement", "Sala 1").containsEntry("issuer_address_district", "Centro")
                .containsEntry("issuer_address_city_name", "Recife").containsEntry("issuer_address_zip_code", "50000000")
                .containsEntry("issuer_address_phone", "8133330000").containsEntry("issuer_address_country_code", "1058")
                .containsEntry("issuer_address_country_name", "Brasil").containsEntry("recipient_address_street", "Rua B")
                .containsEntry("recipient_address_number", "20").containsEntry("recipient_address_complement", "Galpão")
                .containsEntry("recipient_address_district", "Boa Vista").containsEntry("recipient_address_city_name", "Recife")
                .containsEntry("recipient_address_zip_code", "50100000").containsEntry("recipient_address_phone", "8133331111")
                .containsEntry("recipient_address_country_code", "1058").containsEntry("recipient_address_country_name", "Brasil")
                .containsEntry("icms_base", money("1000.00")).containsEntry("icms_amount", money("120.00"))
                .containsEntry("icms_relief_amount", money("1.11")).containsEntry("fcp_amount", money("2.22"))
                .containsEntry("icms_st_base", money("3.33")).containsEntry("icms_st_amount", money("10.00"))
                .containsEntry("fcp_st_amount", money("4.44")).containsEntry("products_amount", money("990.00"))
                .containsEntry("freight_amount", money("5.55")).containsEntry("insurance_amount", money("6.66"))
                .containsEntry("discount_amount", money("7.77")).containsEntry("import_tax_amount", money("8.88"))
                .containsEntry("ipi_amount", money("5.00")).containsEntry("other_expenses_amount", money("9.99"))
                .containsEntry("approximate_taxes_amount", money("12.34")).containsEntry("pis_amount", money("6.50"))
                .containsEntry("cofins_amount", money("30.00")).containsEntry("nfse_layout", null).containsEntry("cte_is_issuer", null);
        // K2: blank CNPJ is absent, the CPF wins; a done review is kept
        assertThat(document(jdbc, doc("k2"))).containsEntry("issuer_document", SUPPLIER_CPF).containsEntry("issuer_document_type", "CPF")
                .containsEntry("review_status", "REVIEWED").containsEntry("reviewed_by", "fiscal1@exemplo.test")
                .containsEntry("reviewed_at", LocalDateTime.of(2026, 11, 7, 10, 0)).containsEntry("review_note", "ok");
        assertThat(document(jdbc, doc("k3"))).containsEntry("model", "55").containsEntry("source_system", "UPLOAD");
        assertThat(document(jdbc, doc("k4"))).containsEntry("status", "CANCELED")
                .containsEntry("canceled_at", LocalDateTime.of(2026, 10, 20, 10, 0));
        // K5: own issue, foreign recipient, SEFAZ_DIST → SEFAZ_DFE (CT-FD-31)
        assertThat(document(jdbc, doc("k5"))).containsEntry("company_role", "ISSUER").containsEntry("direction", "OUTBOUND")
                .containsEntry("recipient_document", "X-99887766").containsEntry("recipient_document_type", "FOREIGN")
                .containsEntry("source_system", "SEFAZ_DFE");
        // CT-FD-04: model 65 = NFC-e
        assertThat(document(jdbc, doc("k6"))).containsEntry("document_type", "NFCE").containsEntry("model", "65")
                .containsEntry("recipient_document", "39053344705").containsEntry("recipient_document_type", "CPF");
        // CT-e: common block, ICMS base and value only, model 57, version 0
        assertThat(document(jdbc, doc("k7"))).containsEntry("document_type", "CTE").containsEntry("model", "57")
                .containsEntry("metadata_version", 0).containsEntry("total_amount", money("150.00"))
                .containsEntry("icms_base", money("150.00")).containsEntry("icms_amount", money("18.00"))
                .containsEntry("pis_amount", null).containsEntry("header_uf_code", null).containsEntry("rectified_file_path", null);
        // CT-FD-11, 17: own_issue null → RECIPIENT; no date, no key; metadata null → 0
        assertThat(document(jdbc, doc("k8"))).containsEntry("company_role", "RECIPIENT").containsEntry("issue_date", null)
                .containsEntry("access_key", null).containsEntry("metadata_version", 0);
        assertThat(document(jdbc, doc("k9"))).containsEntry("issue_date", LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        // CT-FD-15: same key in E1 and E2
        assertThat(document(jdbc, doc("e2k1"))).containsEntry("company_id", E2).containsEntry("access_key", key(1))
                .containsEntry("company_role", "ISSUER");
        // I-02: authorized keeps no canceled_at; deleted copied
        assertThat(document(jdbc, doc("e2k2"))).containsEntry("status", "AUTHORIZED").containsEntry("canceled_at", null)
                .containsEntry("deleted", true);
        assertThat(document(jdbc, doc("e3k1"))).containsEntry("company_id", E3);

        // NFS-e (CT-FD-02, 19)
        Map<String, Object> s1 = document(jdbc, doc("s1"));
        assertThat(s1).containsEntry("document_type", "NFSE").containsEntry("model", null).containsEntry("number", "77")
                .containsEntry("direction", "INBOUND").containsEntry("company_role", "TAKER").containsEntry("status", "AUTHORIZED")
                .containsEntry("issuer_document", SUPPLIER_CNPJ).containsEntry("issuer_document_type", "CNPJ")
                .containsEntry("issuer_name", "Prestador Exemplo").containsEntry("issuer_city_code", "2611606")
                .containsEntry("issuer_uf", null).containsEntry("recipient_document", E1_CNPJ)
                .containsEntry("recipient_document_type", "CNPJ").containsEntry("recipient_name", "Empresa 1")
                .containsEntry("total_amount", money("1200.00")).containsEntry("withheld_pis_amount", null)
                .containsEntry("withheld_cofins_amount", null).containsEntry("withheld_csll_amount", money("12.00"))
                .containsEntry("withheld_irrf_amount", money("18.00")).containsEntry("withheld_inss_amount", money("13.20"))
                .containsEntry("source_system", "NFSE_ADN").containsEntry("metadata_version", 0)
                .containsEntry("nfse_layout", "NATIONAL").containsEntry("nfse_verification_code", "ABC123")
                .containsEntry("nfse_competence", Date.valueOf(LocalDate.of(2026, 10, 1))).containsEntry("nfse_incidence_city_code", "2611606")
                .containsEntry("nfse_service_code", "1.07").containsEntry("nfse_municipal_tax_code", "010701")
                .containsEntry("nfse_service_description", "x".repeat(1000)).containsEntry("nfse_deductions_amount", money("100.00"))
                .containsEntry("iss_base", money("1200.00")).containsEntry("iss_rate", money("2.0000"))
                .containsEntry("iss_amount", money("24.00")).containsEntry("iss_withheld", true)
                .containsEntry("withheld_iss_amount", money("24.00")).containsEntry("pis_amount", money("7.80"))
                .containsEntry("cofins_amount", money("36.00")).containsEntry("nfse_pis_cofins_withheld", true)
                .containsEntry("nfse_net_amount", money("1088.80"));
        assertThat(document(jdbc, doc("s2"))).containsEntry("nfse_layout", "ABRASF").containsEntry("issuer_document_type", "CPF")
                .containsEntry("recipient_document", null).containsEntry("recipient_document_type", null)
                .containsEntry("iss_withheld", false).containsEntry("nfse_pis_cofins_withheld", false);
        assertThat(document(jdbc, doc("s3"))).containsEntry("direction", "OUTBOUND").containsEntry("company_role", "ISSUER")
                .containsEntry("status", "CANCELED").containsEntry("canceled_at", null);
        assertThat(document(jdbc, doc("s4"))).containsEntry("direction", null).containsEntry("company_role", "THIRD_PARTY")
                .containsEntry("recipient_document", "NIF123").containsEntry("recipient_document_type", null);
        assertThat(document(jdbc, doc("s5"))).containsEntry("total_amount", money("9999999999999.99"))
                .containsEntry("iss_rate", money("5.0000"));

        // CT-FD-01 and 02 as the parity groups see them
        assertThat(jdbc.queryForMap("SELECT COUNT(*) n, SUM(status = 'CANCELED') canceled, SUM(total_amount) total"
                + " FROM fiscal_documents WHERE company_id = ? AND document_type = 'NFE' AND direction = 'INBOUND'"
                + " AND issue_date >= '2026-10-01' AND issue_date < '2026-10-31'", E1))
                .containsEntry("n", 4L).containsEntry("canceled", money("1")).containsEntry("total", money("4300.00"));
        assertThat(jdbc.queryForMap("SELECT COUNT(*) n, SUM(total_amount) total, SUM(withheld_iss_amount) iss,"
                + " SUM(withheld_irrf_amount) irrf FROM fiscal_documents WHERE company_id = ? AND document_type = 'NFSE'"
                + " AND direction = 'INBOUND'", E1))
                .containsEntry("n", 2L).containsEntry("total", money("2000.00")).containsEntry("iss", money("24.00"))
                .containsEntry("irrf", money("18.00"));

        // items (CT-FD-03): same id, CFOP trimmed, rates widened without rounding
        Map<String, Object> item = jdbc.queryForMap("SELECT * FROM fiscal_document_items WHERE id = 'i1000000-0000-4000-8000-000000000001'");
        assertThat(item).containsEntry("document_id", doc("k1")).containsEntry("item_number", 1).containsEntry("product_code", "P-1")
                .containsEntry("barcode", "7890000000001").containsEntry("description", "Tecido algodão").containsEntry("ncm", "52081100")
                .containsEntry("cest", "1234567").containsEntry("benefit_code", "PE000001").containsEntry("cfop", "1102")
                .containsEntry("commercial_unit", "M").containsEntry("taxable_unit", "KG")
                .containsEntry("commercial_quantity", money("10.5000")).containsEntry("commercial_unit_price", money("57.1429"))
                .containsEntry("taxable_quantity", money("2.1234")).containsEntry("taxable_unit_price", money("282.5658"))
                .containsEntry("total_amount", money("600.00")).containsEntry("goods_origin", "0").containsEntry("icms_cst", "00")
                .containsEntry("icms_csosn", "102").containsEntry("icms_base_modality", "3").containsEntry("icms_st_base_modality", "4")
                .containsEntry("icms_base", money("600.00")).containsEntry("icms_amount", money("72.00"))
                .containsEntry("icms_relief_amount", money("0.50")).containsEntry("icms_st_base", money("700.00"))
                .containsEntry("icms_st_amount", money("6.00")).containsEntry("icms_rate", money("12.0000"))
                .containsEntry("icms_base_reduction", money("33.3333")).containsEntry("icms_st_mva", money("40.0000"))
                .containsEntry("icms_st_rate", money("18.0000")).containsEntry("ipi_cst", "50").containsEntry("pis_cst", "01")
                .containsEntry("cofins_cst", "01").containsEntry("ipi_base", money("600.00")).containsEntry("ipi_amount", money("3.00"))
                .containsEntry("pis_base", money("600.00")).containsEntry("pis_amount", money("3.90"))
                .containsEntry("cofins_base", money("600.00")).containsEntry("cofins_amount", money("18.00"))
                .containsEntry("ipi_rate", money("0.5000")).containsEntry("pis_rate", money("0.6500"))
                .containsEntry("cofins_rate", money("3.0000")).containsEntry("ibs_cbs_cst", "000")
                .containsEntry("ibs_cbs_class_code", "000001").containsEntry("ibs_cbs_base", money("600.00"))
                .containsEntry("ibs_state_amount", money("0.60")).containsEntry("ibs_city_amount", money("0.00"))
                .containsEntry("ibs_amount", money("0.60")).containsEntry("cbs_amount", money("5.40"))
                .containsEntry("ibs_state_rate", money("0.1000")).containsEntry("cbs_rate", money("0.9000"))
                .containsEntry("ibs_cbs_deferral_or_refund", false).containsEntry("simples_credit_rate", money("1.2500"))
                .containsEntry("simples_credit_amount", money("7.50"));
        assertThat(jdbc.queryForObject("SELECT SUM(total_amount) FROM fiscal_document_items WHERE document_id = ? AND cfop = '1102'",
                BigDecimal.class, doc("k1"))).isEqualByComparingTo("1000.00");

        // events
        assertThat(jdbc.queryForMap("SELECT * FROM fiscal_document_events WHERE id = 'ev100000-0000-4000-8000-000000000001'"))
                .containsEntry("company_id", E1).containsEntry("document_id", doc("k4")).containsEntry("access_key", key(4))
                .containsEntry("event_type", "110111").containsEntry("sequence_number", 1).containsEntry("description", "Cancelamento")
                .containsEntry("event_at", LocalDateTime.of(2026, 10, 20, 10, 0)).containsEntry("protocol", "126260000000001")
                .containsEntry("event_text", "<evento/>").containsEntry("registered", true);
        assertThat(jdbc.queryForMap("SELECT document_id, registered FROM fiscal_document_events"
                + " WHERE id = 'ev100000-0000-4000-8000-000000000002'")).containsEntry("document_id", null).containsEntry("registered", false);

        // sources (CT-FD-27)
        assertThat(jdbc.queryForList("SELECT CONCAT(document_id, ' ', source, ' ', first_seen_at, ' ', last_seen_at)"
                + " FROM fiscal_document_sources ORDER BY document_id, source", String.class)).containsExactly(
                doc("k1") + " SEFAZ_DFE 2026-11-05 09:15:00.000000 2026-11-05 09:15:00.000000",
                doc("k1") + " SIEG 2026-11-05 08:00:00.000000 2026-11-05 08:00:00.000000",
                doc("k2") + " SIEG 2026-11-06 08:00:00.000000 2026-11-06 08:00:00.000000",
                doc("k3") + " UPLOAD 2026-11-03 14:00:00.000000 2026-11-03 14:00:00.000000",
                doc("k5") + " SEFAZ_DFE 2026-11-07 08:00:00.000000 2026-11-07 08:00:00.000000",
                doc("k7") + " UPLOAD 2026-11-02 08:00:00.000000 2026-11-02 08:00:00.000000",
                doc("k8") + " UPLOAD 2026-11-02 08:00:00.000000 2026-11-02 08:00:00.000000",
                doc("s1") + " NFSE_ADN 2026-11-08 08:00:00.000000 2026-11-08 08:00:00.000000",
                doc("s2") + " SIEG 2026-11-09 08:00:00.000000 2026-11-09 08:00:00.000000",
                doc("s4") + " UPLOAD 2026-11-04 09:00:00.000000 2026-11-04 09:00:00.000000");

        // capture counts (CT-FD-29, 36)
        assertThat(jdbc.queryForList("SELECT CONCAT_WS('|', company_id, side, period_month, IFNULL(window_start, '-'),"
                + " IFNULL(window_end, '-'), IFNULL(provider_total, '-'), IFNULL(provider_counted_at, '-'), imported_total,"
                + " IFNULL(stored_any_source_total, '-'), IFNULL(known_gap, '-'), IFNULL(imported_counted_at, '-'),"
                + " IF(closed_at IS NULL, 'open', 'closed'), channel, document_type) FROM capture_period_counts"
                + " ORDER BY company_id, side, period_month", String.class)).containsExactly(
                E1 + "|ISSUED|2026-11|2026-11-01|2026-11-30|12|2026-11-26 10:05:00.000000|12|12|3|2026-11-26 10:05:00.000000|open|SIEG|NFE",
                E1 + "|RECEIVED|2026-09|-|-|-|-|0|-|1|-|closed|SIEG|NFE",
                E1 + "|RECEIVED|2026-10|-|-|55|-|55|-|2|-|closed|SIEG|NFE",
                E1 + "|RECEIVED|2026-11|2026-11-01|2026-11-30|40|2026-11-26 10:00:00.000000|37|42|-|2026-11-26 10:00:00.000000|open|SIEG|NFE",
                E2 + "|ISSUED|2026-10|-|-|5|-|4|-|-|-|closed|SIEG|NFE",
                E2 + "|RECEIVED|2026-11|2026-11-01|2026-11-30|8|2026-11-25 10:00:00.000000|8|-|-|2026-11-25 10:00:00.000000|open|SIEG|NFE",
                E3 + "|RECEIVED|2026-11|-|-|-|-|0|-|1|-|closed|SIEG|NFE");

        // V89 (CT-FD-35, CA-FD-23)
        flyway("89").migrate();
        for (LegacyTable table : LegacyTable.values()) {
            assertThat(tableExists(jdbc, table.originalName())).as(table.originalName()).isFalse();
            assertThat(tableExists(jdbc, table.legacyName())).as(table.legacyName()).isFalse();
        }
        assertThat(columns(jdbc, "fiscal_xml_sync_control")).containsExactlyInAnyOrder("id", "deleted",
                "issued_events_checked_on", "received_events_checked_on", "created_date", "last_modified_date", "company_id",
                "created_by", "last_modified_by", "service_invoices_checked_on", "last_attempt_at");
        assertThat(jdbc.queryForObject("SELECT last_attempt_at FROM fiscal_xml_sync_control WHERE id = 's-e1'", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents", Integer.class)).isEqualTo(18);
        // FD-RN-21: the natural keys are untouched
        assertThat(jdbc.queryForList("SELECT * FROM pendencies")).isEqualTo(pendenciesBefore);
        assertThat(jdbc.queryForList("SELECT * FROM retention_reviews")).isEqualTo(reviewsBefore);
        assertThat(logged("fiscal.documents.cutover.legacy.dropped tables=4")).isTrue();
        assertThat(log("fiscal.documents.cutover.counters.dropped")).contains("columns=24").containsAnyOf("algorithm=INSTANT", "algorithm=INPLACE");
        assertThat(flyway("89").validateWithResult().validationSuccessful).isTrue();
    }

    // ------------------------------------------------------------------ CA-FD-01, 24; CT-FD-33

    @Test
    void newDatabaseCreatesTheTablesAndHasNothingToCopy() {
        flyway("latest").migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);

        assertThat(jdbc.queryForObject("SELECT @@innodb_strict_mode", Integer.class)).isEqualTo(1);
        for (String table : FiscalDocumentTables.TABLES) {
            assertThat(tableExists(jdbc, table)).as(table).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT row_format FROM information_schema.tables WHERE table_schema = ? AND table_name"
                + " = 'fiscal_documents'", String.class, SCRATCH)).isEqualTo("Dynamic");
        assertThat(columns(jdbc, "fiscal_documents")).hasSize(156);
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = ? AND table_name"
                + " = 'fiscal_documents' AND column_name = 'nfse_service_description'", String.class, SCRATCH)).isEqualTo("text");
        assertThat(indexColumns(jdbc, "fiscal_documents", "uk_fiscal_documents_company_key")).containsExactly("company_id", "access_key");
        assertThat(indexColumns(jdbc, "fiscal_documents", "idx_fd_company_type_direction_issue"))
                .containsExactly("company_id", "document_type", "direction", "issue_date");
        assertThat(indexColumns(jdbc, "fiscal_documents", "idx_fd_company_type_source_created"))
                .containsExactly("company_id", "document_type", "source_system", "created_date");
        assertThat(indexColumns(jdbc, "fiscal_documents", "idx_fd_purge")).containsExactly("xml_purged_at", "issue_date");
        assertThat(indexColumns(jdbc, "fiscal_document_items", "idx_fdi_document_cfop_amount"))
                .containsExactly("document_id", "cfop", "total_amount");
        assertThat(indexColumns(jdbc, "fiscal_document_events", "uk_fiscal_document_events"))
                .containsExactly("company_id", "access_key", "event_type", "sequence_number");
        assertThat(indexColumns(jdbc, "fiscal_document_sources", "uk_fiscal_document_sources")).containsExactly("document_id", "source");
        assertThat(indexColumns(jdbc, "capture_period_counts", "uk_capture_period_counts"))
                .containsExactly("company_id", "channel", "document_type", "side", "period_month");
        assertThat(jdbc.queryForList("SELECT constraint_name FROM information_schema.referential_constraints WHERE"
                + " constraint_schema = ? AND constraint_name LIKE 'fk\\_%' AND table_name IN ('fiscal_documents',"
                + " 'fiscal_document_items', 'fiscal_document_events', 'fiscal_document_sources', 'capture_period_counts')"
                + " ORDER BY 1", String.class, SCRATCH)).containsExactlyInAnyOrder("fk_capture_period_counts_company",
                "fk_fiscal_document_events_company", "fk_fiscal_document_events_document", "fk_fiscal_document_items_document",
                "fk_fiscal_document_sources_document", "fk_fiscal_documents_company");

        // FD-RN-02: the CHECK is enforced (MySQL 8.0.16+; 5.7 parses and ignores it, the domain still guards)
        LegacyFixtures.companies(jdbc);
        boolean keepsChecks = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema ="
                + " 'information_schema' AND table_name = 'CHECK_CONSTRAINTS'", Integer.class) > 0;
        if (keepsChecks) assertThatThrownBy(() -> jdbc.update("INSERT INTO fiscal_documents (id, company_id, document_type, company_role, status,"
                + " source_system, metadata_version, created_by, created_date, deleted) VALUES ('x', ?, 'MDFE', 'ISSUER',"
                + " 'AUTHORIZED', 'UPLOAD', 0, 'qa', NOW(), 0)", E1)).hasMessageContaining("chk_fiscal_documents_document_type");

        // a database built by Flyway from zero has the old tables, empty: renamed, nothing copied, parity on zero rows, dropped
        assertThat(logged("fiscal.documents.cutover.renamed tables=4")).isTrue();
        assertThat(log("fiscal.documents.cutover.copied step=documents")).contains("copied=0");
        assertThat(log("fiscal.documents.cutover.parity.ok")).contains("goods=0").contains("capture-counts=0");
        assertThat(logged("fiscal.documents.cutover.legacy.dropped tables=4")).isTrue();
        for (LegacyTable table : LegacyTable.values()) {
            assertThat(tableExists(jdbc, table.originalName())).isFalse();
            assertThat(tableExists(jdbc, table.legacyName())).isFalse();
        }
        assertThat(columns(jdbc, "fiscal_xml_sync_control")).doesNotContain("total_sieg", "received_prev_month");

        // Flyway sees exactly V86-V89 in the new location, and the application's location doesn't see them
        assertThat(Arrays.stream(flyway("latest").info().applied()).map(MigrationInfo::getVersion)
                .filter(v -> v.isNewerThan("85")).map(Object::toString)).containsExactly("86", "86.1", "86.2", "86.3", "86.4", "87", "88", "89");
        assertThat(Arrays.stream(flyway("latest", "classpath:db/migration").info().all())
                .noneMatch(info -> info.getScript().contains("fiscaldocuments") && info.getState().isResolved())).isTrue();
    }

    /** A database without the four old tables (already removed): V87 to V89 log "nothing to do" and only drop the counters. */
    @Test
    void withoutTheOldTablesV87ToV89HaveNothingToDo() {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        jdbc.execute("DROP TABLE fiscal_xml_events, fiscal_xml_document_items, fiscal_xml_documents, service_invoices");

        flyway("latest").migrate();

        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("fiscal.documents.cutover.nothing.to.do reason=no-legacy-tables"))).hasSize(3);
        assertThat(log("fiscal.documents.cutover.parity.ok")).isEqualTo("fiscal.documents.cutover.parity.ok groups={capture-counts=0}");
        assertThat(columns(jdbc, "fiscal_xml_sync_control")).doesNotContain("total_sieg", "ultima_verificacao_em");
        assertThat(historySuccess(jdbc, "89")).isTrue();
    }

    static List<String> indexColumns(JdbcTemplate jdbc, String table, String index) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = ?"
                + " AND index_name = ? ORDER BY seq_in_index", String.class, SCRATCH, table, index);
    }

    // ------------------------------------------------------------------ CA-FD-03; CT-FD-12

    @Test
    void cutoverInterruptedMidCopyResumesWithoutDuplicates() {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        LegacyFixtures.scenario(jdbc);
        // 12,000 more items on K1: the copy goes in batches of 5,000
        jdbc.execute("INSERT /*+ SET_VAR(cte_max_recursion_depth = 1000000) */ INTO fiscal_xml_document_items (id, document_id, numero_item, cfop, valor_total)"
                + " WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 12000)"
                + " SELECT CONCAT('j', LPAD(i, 35, '0')), '" + doc("k1") + "', i, '1102', 1.00 FROM n");
        // the third batch fails (a stand-in for a lock timeout or a kill in the middle of the copy)
        flyway("86.4").migrate();
        jdbc.execute("CREATE TRIGGER fd_fail BEFORE INSERT ON fiscal_document_items FOR EACH ROW BEGIN"
                + " IF NEW.id = CONCAT('j', LPAD(10500, 35, '0')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'interrupted';"
                + " END IF; END");

        assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(FlywayException.class);
        assertThat(tableExists(jdbc, "legacy_fiscal_xml_documents")).isTrue();
        assertThat(tableExists(jdbc, "fiscal_xml_documents")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents", Integer.class)).isEqualTo(18);
        long itemsAfterFailure = jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_document_items", Long.class);
        assertThat(itemsAfterFailure).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_document_events", Integer.class)).isZero();
        assertThat(historySuccess(jdbc, "87")).isFalse();

        jdbc.execute("DROP TRIGGER fd_fail");
        flyway("latest").repair();
        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_document_items", Integer.class)).isEqualTo(12_003);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM fiscal_document_items", Integer.class)).isEqualTo(12_003);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_document_sources", Integer.class)).isEqualTo(10);
        assertThat(log("fiscal.documents.cutover.copied step=items")).contains("copied=2003").contains("existing=10000");
        assertThat(log("fiscal.documents.cutover.copied step=documents")).contains("copied=0");
        assertThat(logged("fiscal.documents.cutover.rename skipped=already-renamed")).isTrue();
        assertThat(logged("fiscal.documents.cutover.parity.ok")).isTrue();
        assertThat(historySuccess(jdbc, "89")).isTrue();
    }

    // ------------------------------------------------------------------ CA-FD-04; CT-FD-13

    @Test
    void idCollisionStopsBeforeTheRenameWithoutCopying() {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        LegacyFixtures.scenario(jdbc);
        LegacyFixtures.insert(jdbc, "service_invoices", LegacyFixtures.nfse(doc("k2"), E1, "access_key", "2611606" + "9".repeat(43)));

        assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(FlywayException.class)
                .rootCause().hasMessageContaining("1 id(s) of service_invoices also in fiscal_xml_documents");

        assertThat(log("fiscal.documents.cutover.id.collision")).isEqualTo("fiscal.documents.cutover.id.collision count=1");
        for (LegacyTable table : LegacyTable.values()) {
            assertThat(tableExists(jdbc, table.originalName())).isTrue();
            assertThat(tableExists(jdbc, table.legacyName())).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents", Integer.class)).isZero();
        assertThat(historySuccess(jdbc, "86")).isTrue();
        assertThat(historySuccess(jdbc, "87")).isFalse();
    }

    // ------------------------------------------------------------------ CA-FD-25; CT-FD-34

    @Test
    void parityDifferenceFailsV88AndKeepsTheLegacy() {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        LegacyFixtures.scenario(jdbc);
        flyway("87").migrate();
        jdbc.update("UPDATE fiscal_documents SET total_amount = total_amount - 0.01 WHERE id = ?", doc("k1"));

        assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(FlywayException.class)
                .rootCause().hasMessageContaining("fiscal documents parity failed in [goods]");

        assertThat(log("fiscal.documents.cutover.parity.mismatch block=goods"))
                .isEqualTo("fiscal.documents.cutover.parity.mismatch block=goods groups=1 keys=[[" + E1 + ", 2026-10, NFE, INBOUND]]");
        assertThat(historySuccess(jdbc, "88")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '89'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_fiscal_xml_documents", Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_fiscal_xml_document_items", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_service_invoices", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_fiscal_xml_events", Integer.class)).isEqualTo(2);
        assertThat(columns(jdbc, "fiscal_xml_sync_control")).contains("total_sieg", "received_prev_month");

        // V89 refuses on its own too, without a successful V88 in the history (e.g. run by hand after a repair)
        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '88'");
        try (Connection connection = DriverManager.getConnection(url(SCRATCH), System.getenv().getOrDefault("DB_USER", "root"),
                System.getenv().getOrDefault("DB_PASSWORD", "root"))) {
            Context context = mock(Context.class);
            Configuration configuration = mock(Configuration.class);
            when(context.getConnection()).thenReturn(connection);
            when(context.getConfiguration()).thenReturn(configuration);
            when(configuration.getTable()).thenReturn("flyway_schema_history");
            assertThatThrownBy(() -> new V89__DropLegacyFiscalDocuments().migrate(context))
                    .hasMessageContaining("V88 (parity) has not succeeded");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(tableExists(jdbc, "legacy_fiscal_xml_documents")).isTrue();
    }
}
