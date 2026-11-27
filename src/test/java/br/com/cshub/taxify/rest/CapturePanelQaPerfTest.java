package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.config.CapturePanelProperties;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q-CP-2 / CA-CP-21: desempenho do painel com 5.000 empresas ficticias em 50 escritorios. Mede p50/p95 da lista (plataforma e
 * ADMIN de um escritorio de 100 empresas), do detalhe e dos filtros, e o numero de SELECTs com 50 e com 5.000 empresas.
 *
 * <p><b>So roda com QA_PERF=1 e no banco descartavel {@code taxify_qa_perf} do MySQL local</b> (criado e apagado por quem roda
 * a medicao; o teste recusa qualquer outro DB_NAME). Nada de dado real: nomes, CNPJs e e-mails gerados.
 */
@EnabledIfEnvironmentVariable(named = "QA_PERF", matches = "1", disabledReason = "medicao manual: QA_PERF=1 e DB_NAME=taxify_qa_perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxify.platform.admin-logins=qa.perf.csadmin",
        "taxify.platform.firm-id=" + CapturePanelQaPerfTest.PLATFORM_FIRM,
        "taxify.capture-panel.platform-enabled=true",
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=true", "fiscal-xml.sync.cron=0 0/5 * * * *", "fiscal-xml.sync.batch-size=2",
        "fiscal-xml.sync.reference-date=2026-11-27",
        "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "sefaz.dist.ufs=PE", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
class CapturePanelQaPerfTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-0000000c0de3";
    private static final String CSADMIN = "qa.perf.csadmin";
    private static final String PASSWORD = "SenhaDeTeste2026";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 10, 0);
    private static final Random RANDOM = new Random(42);

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        MutableClock qaPerfClock() {
            return new MutableClock(NOW);
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CapturePanelProperties panelProperties;
    @Autowired
    private FiscalXmlSyncProperties syncProperties;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> firmIds = new ArrayList<>();
    private final List<List<String>> companiesByFirm = new ArrayList<>();
    private String planId;

    @Test
    void fiveThousandCompaniesInFiftyFirms() throws Exception {
        String db = jdbc.queryForObject("SELECT DATABASE()", String.class);
        Assumptions.assumeTrue("taxify_qa_perf".equals(db), "so no banco descartavel taxify_qa_perf, conectado em " + db);
        String url = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        assertThat(url).startsWith("jdbc:mysql://localhost:");
        panelProperties.setPlatformEnabled(true);

        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', 'CS Hub perf')", PLATFORM_FIRM, now);
        user(PLATFORM_FIRM, CSADMIN, "ADMIN");
        for (int f = 0; f < 50; f++) {
            String firm = UUID.randomUUID().toString();
            firmIds.add(firm);
            jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)", firm, now,
                    "Escritorio Perf " + f);
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                    + " VALUES (?, ?, ?, 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), firm, f % 10 == 9 ? "BLOCKED" : "ACTIVE", now);
            user(firm, "admin.perf" + f, "ADMIN");
            companiesByFirm.add(new ArrayList<>());
        }
        // plano unico por escritorio seria mais fiel; um plano por escritorio: 50 planos
        List<String> plans = new ArrayList<>();
        for (String firm : firmIds) {
            String plan = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO plans (id, deleted, created_date, created_by, name, accounting_firm_id) VALUES (?, 0, ?, 'qa', 'Plano', ?)", plan, now, firm);
            for (PlanServiceEnum service : List.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION,
                    PlanServiceEnum.SEFAZ_INTEGRATION, PlanServiceEnum.NFSE_NATIONAL_INTEGRATION)) {
                jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, ?)", plan, service.name());
            }
            plans.add(plan);
        }
        planId = plans.getFirst();

        // ---- baseline: 50 empresas (1 por escritorio)
        seed(1, plans);
        String csadmin = token(CSADMIN);
        warm(csadmin);
        long smallSelects = selects(() -> get("/platform/capture-panel?size=50", csadmin));
        long smallDetail = selects(() -> get("/platform/capture-panel/companies/" + companiesByFirm.getFirst().getFirst(), csadmin));
        System.out.println("QA-PERF baseline 50 empresas: SELECTs lista=" + smallSelects + " detalhe=" + smallDetail);

        // ---- 5.000 empresas
        long seedStart = System.currentTimeMillis();
        seed(99, plans);
        System.out.println("QA-PERF seed 5.000 empresas em " + (System.currentTimeMillis() - seedStart) + " ms");
        int total = jdbc.queryForObject("SELECT COUNT(*) FROM companies", Integer.class);
        assertThat(total).isEqualTo(5000);
        jdbc.execute("ANALYZE TABLE companies, fiscal_xml_sync_control, fiscal_xml_documents, capture_stage_status, subscriptions");

        warm(csadmin);
        long bigSelects = selects(() -> get("/platform/capture-panel?size=50", csadmin));
        long bigDetail = selects(() -> get("/platform/capture-panel/companies/" + companiesByFirm.getFirst().getFirst(), csadmin));
        System.out.println("QA-PERF 5.000 empresas: SELECTs lista=" + bigSelects + " detalhe=" + bigDetail);
        assertThat(bigSelects).as("o numero de consultas nao cresce com as empresas").isEqualTo(smallSelects);
        assertThat(bigDetail).isEqualTo(smallDetail);

        report("plataforma size=50 (padrao)", 60, () -> get("/platform/capture-panel?size=50", csadmin));
        report("plataforma size=100 pagina 10", 40, () -> get("/platform/capture-panel?size=100&page=10", csadmin));
        report("plataforma situacao=COM_ERRO", 40, () -> get("/platform/capture-panel?situacao=COM_ERRO&size=50", csadmin));
        report("plataforma situacao=PENDENTE&situacao=ATRASADA canal=SIEG", 40,
                () -> get("/platform/capture-panel?situacao=PENDENTE&situacao=ATRASADA&canal=SIEG&size=50", csadmin));
        report("plataforma busca=perf 12", 40, () -> get("/platform/capture-panel?busca=empresa%20perf%2012&size=50", csadmin));
        report("plataforma sort=name", 40, () -> get("/platform/capture-panel?sort=name&size=50", csadmin));
        report("plataforma sort=nextAttempt", 40, () -> get("/platform/capture-panel?sort=nextAttempt&size=50", csadmin));
        report("plataforma tipo=EVENTOS", 40, () -> get("/platform/capture-panel?tipo=EVENTOS&size=50", csadmin));
        report("plataforma firmId (100 empresas)", 40, () -> get("/platform/capture-panel?firmId=" + firmIds.get(3) + "&size=50", csadmin));
        report("plataforma incluirInativas=true", 40, () -> get("/platform/capture-panel?incluirInativas=true&size=50", csadmin));
        report("detalhe da empresa (plataforma)", 40,
                () -> get("/platform/capture-panel/companies/" + companiesByFirm.get(5).get(7), csadmin));
        String admin3 = token("admin.perf3");
        report("ADMIN do escritorio (100 empresas) size=50", 60, () -> get("/capture-panel?size=50", admin3));
        String admin9 = token("admin.perf9");
        report("ADMIN do escritorio BLOQUEADO (100 empresas)", 40, () -> get("/capture-panel?size=50", admin9));
        // concorrencia: 8 usuarios ao mesmo tempo na lista da plataforma
        List<Long> parallel = Collections.synchronizedList(new ArrayList<>());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        long wall = System.nanoTime();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(executor.submit(() -> {
                for (int k = 0; k < 5; k++) {
                    long t = System.nanoTime();
                    get("/platform/capture-panel?size=50", csadmin);
                    parallel.add((System.nanoTime() - t) / 1_000_000);
                }
                return null;
            }));
        }
        for (var future : futures) {
            future.get();
        }
        executor.shutdown();
        Collections.sort(parallel);
        System.out.println("QA-PERF 8 usuarios simultaneos x5 (plataforma, size=50): n=" + parallel.size() + " p50="
                + parallel.get(parallel.size() / 2) + " ms p95=" + parallel.get((int) Math.ceil(parallel.size() * 0.95) - 1)
                + " ms max=" + parallel.getLast() + " ms (total " + (System.nanoTime() - wall) / 1_000_000 + " ms)");
        // CA-CP-21: size=500 -> 400
        assertThat(rawStatus("/platform/capture-panel?size=500", csadmin)).isEqualTo(400);
    }

    // ------------------------------------------------------------------ seed

    private void seed(int perFirm, List<String> plans) {
        Timestamp created = Timestamp.valueOf(NOW);
        List<Object[]> documents = new ArrayList<>();
        List<Object[]> companies = new ArrayList<>();
        List<Object[]> subscriptions = new ArrayList<>();
        List<Object[]> controls = new ArrayList<>();
        List<Object[]> stages = new ArrayList<>();
        List<Object[]> certificates = new ArrayList<>();
        List<Object[]> sefaz = new ArrayList<>();
        List<Object[]> notes = new ArrayList<>();
        for (int f = 0; f < firmIds.size(); f++) {
            for (int i = 0; i < perFirm; i++) {
                int sequence = companiesByFirm.get(f).size();
                String company = UUID.randomUUID().toString();
                String document = UUID.randomUUID().toString();
                companiesByFirm.get(f).add(company);
                documents.add(new Object[]{document, created, cnpj(String.format("%08d", 10_000_000 + f * 1000 + sequence), "0001")});
                companies.add(new Object[]{company, created, firmIds.get(f), document, "Empresa Perf " + f + "-" + sequence,
                        sequence % 25 != 24 ? 1 : 0, sequence % 11 == 0 ? "PB" : "PE"});
                // 5% sem plano com SIEG
                String plan = sequence % 20 == 19 ? null : plans.get(f);
                if (plan != null) {
                    subscriptions.add(new Object[]{UUID.randomUUID().toString(), created, java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)),
                            java.sql.Date.valueOf(LocalDate.of(2027, 12, 31)), company, plan});
                }
                // situacoes variadas: 5% nunca tentada, restante com verificacao entre 0 e 60 h atras
                boolean never = sequence % 20 == 3;
                int hoursAgo = RANDOM.nextInt(60);
                int total = RANDOM.nextInt(300);
                int imported = RANDOM.nextInt(10) == 0 ? Math.max(0, total - RANDOM.nextInt(20)) : total;
                LocalDateTime checked = NOW.minusHours(hoursAgo);
                controls.add(new Object[]{UUID.randomUUID().toString(), company, created, total, imported, Timestamp.valueOf(checked),
                        total / 4, total / 4, Timestamp.valueOf(checked),
                        never ? null : Timestamp.valueOf(NOW.minusMinutes(RANDOM.nextInt(60 * 20))),
                        java.sql.Date.valueOf(hoursAgo > 40 ? LocalDate.of(2026, 11, 24) : LocalDate.of(2026, 11, 27))});
                if (sequence % 7 == 0) {
                    stages.add(new Object[]{UUID.randomUUID().toString(), company, "SIEG_RECEIVED", "FAILURE", Timestamp.valueOf(NOW.minusHours(2)),
                            Timestamp.valueOf(NOW.minusHours(2)), "SIEG_UNAVAILABLE", "503", 1 + RANDOM.nextInt(5), created});
                }
                if (sequence % 3 == 0) {
                    certificates.add(new Object[]{UUID.randomUUID().toString(), company, Timestamp.valueOf(NOW.plusDays(10 + RANDOM.nextInt(400))), created});
                    sefaz.add(new Object[]{UUID.randomUUID().toString(), company, Timestamp.valueOf(NOW.minusMinutes(RANDOM.nextInt(400))),
                            sequence % 9 == 0 ? "ERRO" : "OK", created});
                }
                // ~20 notas por empresa, de 3 origens
                for (int n = 0; n < 20; n++) {
                    notes.add(new Object[]{UUID.randomUUID().toString(), Timestamp.valueOf(NOW.minusHours(RANDOM.nextInt(24 * 25))),
                            n % 4 == 0 ? "SEFAZ_DIST" : n % 4 == 1 ? "UPLOAD" : "SIEG", company});
                }
            }
        }
        jdbc.batchUpdate("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')", documents);
        jdbc.batchUpdate("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active, uf)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, ?, ?, ?)", companies);
        jdbc.batchUpdate("INSERT INTO subscriptions (id, deleted, created_date, created_by, start_date, end_date, company_id, plan_id)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, ?, ?)", subscriptions);
        jdbc.batchUpdate("INSERT INTO fiscal_xml_sync_control (id, company_id, deleted, created_by, created_date, data_emissao_inicio,"
                + " data_emissao_fim, total_sieg, total_importado, ultima_verificacao_em, issued_total_sieg, issued_total_imported,"
                + " issued_checked_at, last_attempt_at, received_events_checked_on, issued_events_checked_on, service_invoices_checked_on)"
                + " VALUES (?, ?, 0, 'qa', ?, '2026-11-01', '2026-11-27', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                controls.stream().map(c -> new Object[]{c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[10], c[10]}).toList());
        jdbc.batchUpdate("INSERT INTO capture_stage_status (id, company_id, stage, last_outcome, last_outcome_at, last_failure_at, failure_code,"
                + " provider_status, consecutive_failures, deleted, created_by, created_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'qa', ?)", stages);
        jdbc.batchUpdate("INSERT INTO company_certificates (id, company_id, file_path, password_encrypted, valid_until, deleted, created_by,"
                + " created_date) VALUES (?, ?, 'certificados/ficticio.pfx', 'senha-cifrada-ficticia', ?, 0, 'qa', ?)", certificates);
        jdbc.batchUpdate("INSERT INTO sefaz_dist_control (id, company_id, ult_nsu, last_run_at, last_status, deleted, created_by, created_date)"
                + " VALUES (?, ?, 1, ?, ?, 0, 'qa', ?)", sefaz);
        jdbc.batchUpdate("INSERT INTO fiscal_xml_documents (id, deleted, created_date, created_by, document_type, source_system, company_id,"
                + " xml_file_path) VALUES (?, 0, ?, 'qa', 'NFE', ?, ?, 'xml/ficticio.xml')", notes);
    }

    private void user(String firm, String login, String profile) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CPF')", document, now,
                String.format("%011d", RANDOM.nextLong(100_000_000_000L)));
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name, email,"
                        + " password) VALUES (?, 0, ?, 'qa', ?, ?, ?, ?, ?, ?, ?)", id, now, profile, firm, document, login, "Pessoa " + login,
                login + "@exemplo.test", passwordEncoder.encode(PASSWORD));
    }

    // ------------------------------------------------------------------ medicao

    private void warm(String token) throws Exception {
        for (int i = 0; i < 5; i++) {
            get("/platform/capture-panel?size=50", token);
        }
    }

    private void report(String label, int rounds, Call call) throws Exception {
        for (int i = 0; i < 3; i++) {
            call.run();
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            long started = System.nanoTime();
            call.run();
            times.add((System.nanoTime() - started) / 1_000_000);
        }
        Collections.sort(times);
        System.out.println("QA-PERF " + label + ": n=" + rounds + " p50=" + times.get(rounds / 2) + " ms p95="
                + times.get((int) Math.ceil(rounds * 0.95) - 1) + " ms max=" + times.getLast() + " ms");
    }

    @FunctionalInterface
    interface Call {
        void run() throws Exception;
    }

    private long selects(Call call) throws Exception {
        long before = com();
        call.run();
        return com() - before;
    }

    private long com() {
        return Long.parseLong(String.valueOf(jdbc.queryForMap("SHOW GLOBAL STATUS LIKE 'Com_select'").get("Value")));
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpResponse<String> response = send(path, token);
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private int rawStatus(String path, String token) throws Exception {
        return send(path, token).statusCode();
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1" + path))
                .header("Authorization", "Bearer " + token).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String token(String login) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1/auth"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"auth\":\"" + auth + "\"}")).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("login " + login).isEqualTo(200);
        return json.readTree(response.body()).get("token").asText();
    }

    private static String cnpj(String root, String establishment) {
        return CapturePanelIntegrationTest.cnpj(root, establishment);
    }
}
