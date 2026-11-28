package db.fiscaldocuments;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.BEFORE;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.SCRATCH;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.flyway;
import static db.fiscaldocuments.FiscalDocumentsMigrationsTest.jdbc;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volume of the cutover (spec 2026-11-27, 6.1, 8 item 2, FD-RN-30, Q-FD-4): fictitious data the size measured on the
 * EC2 on 2026-11-27 - 1,580 notes (a few NFC-e and CT-e added), 25,039 items, 11,976 NFS-e, 1,515 events, 306
 * companies with their sync control - times {@code FD_VOLUME_FACTOR} (default 1; 10 for the 10x rehearsal). Measures
 * V86 to V89 and checks that everything was copied and the parity passed. Prints only counts and milliseconds.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1")
class FiscalDocumentsCutoverVolumeTest {

    private static final int DOCUMENTS = 1_580;
    private static final int ITEMS = 25_039;
    private static final int SERVICE_INVOICES = 11_976;
    private static final int EVENTS = 1_515;
    private static final int COMPANIES = 306;
    private static final int COMPANIES_WITH_NFE = 59;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(V87__FiscalDocumentsCutover.class),
            (Logger) LoggerFactory.getLogger(V88__FiscalDocumentsParity.class));

    @BeforeEach
    void createScratchDatabase() {
        JdbcTemplate admin = jdbc("");
        admin.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        admin.execute("CREATE DATABASE " + SCRATCH + " CHARACTER SET utf8mb4");
        logs.start();
        loggers.forEach(logger -> logger.addAppender(logs));
    }

    @AfterEach
    void dropScratchDatabase() {
        loggers.forEach(logger -> logger.detachAppender(logs));
        jdbc("").execute("DROP DATABASE IF EXISTS " + SCRATCH);
    }

    private static String series(int upTo) {
        return " WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < " + upTo + ")";
    }

    private static String id(String prefix, String number) {
        return "CONCAT('" + prefix + "', LPAD(" + number + ", " + (36 - prefix.length()) + ", '0'))";
    }

    private static final String HINT = " /*+ SET_VAR(cte_max_recursion_depth = 100000000) */ ";

    @Test
    void cutoverAtTheMeasuredVolume() {
        int factor = Integer.parseInt(System.getenv().getOrDefault("FD_VOLUME_FACTOR", "1"));
        int documents = DOCUMENTS * factor;
        int items = ITEMS * factor;
        int invoices = SERVICE_INVOICES * factor;
        int events = EVENTS * factor;
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        long seedStarted = System.currentTimeMillis();
        String company = id("co", "i");
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES ('f1', 0, NOW(), 'qa', 'F1')");
        jdbc.update("INSERT" + HINT + "INTO documents (id, deleted, created_date, created_by, number, type)" + series(COMPANIES)
                + " SELECT " + id("dc", "i") + ", 0, NOW(), 'qa', LPAD(i, 14, '0'), 'CNPJ' FROM n");
        jdbc.update("INSERT" + HINT + "INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name,"
                + " active)" + series(COMPANIES) + " SELECT " + company + ", 0, NOW(), 'qa', 'f1', " + id("dc", "i")
                + ", CONCAT('Empresa ', i), 1 FROM n");
        String documentCompany = id("co", "1 + i % " + COMPANIES_WITH_NFE);
        jdbc.update("INSERT" + HINT + "INTO fiscal_xml_documents (id, deleted, created_date, created_by, document_type, source_system,"
                + " company_id, xml_file_path, ide_modelo, ide_serie, ide_numero, access_key, direction, own_issue, issue_date,"
                + " issuer_cnpj, recipient_cnpj, emit_nome, dest_nome, emit_end_logradouro, emit_end_municipio, emit_end_uf,"
                + " total_valor_total_nota, total_valor_produtos, total_valor_icms, total_base_calculo_icms, total_valor_pis,"
                + " total_valor_cofins, ret_irrf, total_valor_ibs, total_valor_cbs, status, canceled_at, found_in_sieg_at,"
                + " found_in_sefaz_at, metadata_version, review_status)" + series(documents)
                + " SELECT " + id("a", "i") + ", 0, '2026-11-02 08:00:00', 'qa', IF(i % 400 = 0, 'CTE', 'NFE'),"
                + " CASE WHEN i % 9 = 0 THEN 'SEFAZ_DIST' WHEN i % 31 = 0 THEN 'UPLOAD' ELSE 'SIEG' END, " + documentCompany + ","
                + " CONCAT('xml/2026/11/', i, '.xml'), IF(i % 50 = 0, '65', '55'), '1', i, CONCAT('2626', LPAD(i, 40, '0')),"
                + " IF(i % 7 = 0, 'OUTBOUND', 'INBOUND'), i % 7 = 0, '2026-08-23' + INTERVAL (i % 96) DAY + INTERVAL (i % 86000) SECOND,"
                + " '12345678000195', '11222333000181', 'Fornecedor Exemplo Ltda', 'Empresa Exemplo', 'Rua Exemplo', 'Recife', 'PE',"
                + " 100 + (i % 1000) * 1.37, 100 + (i % 1000) * 1.37, (i % 100) * 0.12, 100 + (i % 1000), (i % 10) * 0.65,"
                + " (i % 10) * 3.00, IF(i % 13 = 0, 1.50, NULL), (i % 5) * 0.10, (i % 5) * 0.90, IF(i % 40 = 0, 'CANCELED', NULL),"
                + " IF(i % 40 = 0, '2026-11-01 10:00:00', NULL), IF(i % 3 = 0, NULL, '2026-11-05 08:00:00' + INTERVAL i SECOND),"
                + " IF(i % 4 = 0, '2026-11-05 09:00:00', NULL), 6, 'PENDING' FROM n");
        jdbc.update("INSERT" + HINT + "INTO fiscal_xml_document_items (id, document_id, numero_item, codigo, descricao, ncm, cfop,"
                + " unidade_comercial, quantidade_comercial, valor_unitario_comercial, valor_total, cst_icms, base_calculo_icms,"
                + " aliquota_icms, valor_icms, cst_pis, valor_pis, cst_cofins, valor_cofins, ibs_valor, cbs_valor)" + series(items)
                // items only on goods invoices: the ids multiple of 400 are CT-e
                + " SELECT " + id("b", "i") + ", " + id("a", "1 + i % " + documents + " - IF((1 + i % " + documents + ") % 400 = 0, 1, 0)") + ", 1 + i DIV " + documents + ","
                + " CONCAT('P-', i % 500), 'Produto de exemplo para o ensaio de volume', '52081100', IF(i % 2 = 0, '1102', '5102 '),"
                + " 'UN', 1 + i % 20, 10.1234, 10.00 + (i % 300), '00', 10.00 + (i % 300), 12.0000, (i % 300) * 0.12, '01', 0.65,"
                + " '01', 3.00, 0.01, 0.09 FROM n");
        jdbc.update("INSERT" + HINT + "INTO service_invoices (id, company_id, access_key, layout, numero, issue_date, competence, direction,"
                + " prestador_documento, prestador_nome, tomador_documento, tomador_nome, descricao_servico, valor_servico,"
                + " base_calculo, aliquota_iss, valor_iss, iss_retido, valor_iss_retido, valor_irrf, valor_pis, valor_cofins,"
                + " pis_cofins_retidos, valor_liquido, canceled, xml_file_path, source_system, found_in_sieg_at, found_in_adn_at,"
                + " deleted, created_by, created_date)" + series(invoices)
                + " SELECT " + id("s", "i") + ", " + id("co", "1 + i % " + COMPANIES) + ", CONCAT('2611606', LPAD(i, 43, '0')),"
                + " IF(i % 5 = 0, 'ABRASF', 'NACIONAL'), i, '2026-08-23' + INTERVAL (i % 96) DAY, '2026-10-01',"
                + " ELT(1 + i % 3, 'PRESTADA', 'TOMADA', 'TERCEIRO'), IF(i % 4 = 0, '52998224725', '12345678000195'), 'Prestador',"
                + " '11222333000181', 'Tomador', REPEAT('Serviço de exemplo. ', 10), 500 + (i % 700), 500 + (i % 700), 2.0000,"
                + " (500 + (i % 700)) * 0.02, i % 6 = 0, IF(i % 6 = 0, (500 + (i % 700)) * 0.02, NULL), IF(i % 8 = 0, 7.50, NULL),"
                + " 3.25, 15.00, i % 8 = 0, 480 + (i % 700), i % 60 = 0, CONCAT('nfse/', i, '.xml'), IF(i % 2 = 0, 'ADN', 'SIEG'),"
                + " IF(i % 2 = 1, '2026-11-06 08:00:00', NULL), IF(i % 2 = 0, '2026-11-06 09:00:00', NULL), 0, 'qa', '2026-11-02 08:00:00'"
                + " FROM n");
        jdbc.update("INSERT" + HINT + "INTO fiscal_xml_events (id, deleted, registered, sequence_number, event_type, created_date,"
                + " event_at, company_id, document_id, access_key, created_by, description)" + series(events)
                + " SELECT " + id("e", "i") + ", 0, 1, 1, IF(i % 2 = 0, '110111', '210210'), '2026-11-02 08:00:00',"
                + " '2026-11-02 08:00:00', " + documentCompany + ", IF(i % 10 = 0, NULL, " + id("a", "i") + "),"
                + " CONCAT('2626', LPAD(i, 40, '0')), 'qa', 'Evento' FROM n");
        jdbc.update("INSERT" + HINT + "INTO fiscal_xml_sync_control (id, company_id, deleted, created_by, created_date, total_sieg,"
                + " total_importado, ultima_verificacao_em, data_emissao_inicio, data_emissao_fim, issued_total_sieg,"
                + " issued_total_imported, issued_checked_at, received_prev_month, received_prev_total_sieg, received_prev_imported)"
                + series(COMPANIES) + " SELECT " + id("sc", "i") + ", " + company + ", 0, 'qa', NOW(), i % 50, i % 45,"
                + " '2026-11-26 10:00:00', '2026-11-01', '2026-11-30', i % 9, i % 9, '2026-11-26 10:05:00',"
                + " IF(i % 2 = 0, '2026-10', NULL), 30, 30 FROM n");
        long seedMs = System.currentTimeMillis() - seedStarted;

        long started = System.currentTimeMillis();
        flyway("latest").migrate();
        long migrateMs = System.currentTimeMillis() - started;

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents", Integer.class)).isEqualTo(documents + invoices);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_document_items", Integer.class)).isEqualTo(items);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_document_events", Integer.class)).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents WHERE document_type = 'NFCE'", Integer.class))
                .as("model 65 that is not a CT-e").isEqualTo(documents / 50 - documents / 400);
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("fiscal.documents.cutover.parity.ok"))).isTrue();
        System.out.printf("fiscal.documents.volume factor=%d documents=%d items=%d serviceInvoices=%d events=%d seedMs=%d"
                + " migrateV86toV89Ms=%d%n", factor, documents, items, invoices, events, seedMs, migrateMs);
        logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("fiscal.documents.cutover.copied") || m.startsWith("fiscal.documents.cutover.parity.ok"))
                .forEach(m -> System.out.println("  " + m));
        assertThat(migrateMs).as("FD-RN-30: the copy at this volume within 5 minutes").isLessThan(300_000L);
    }
}
