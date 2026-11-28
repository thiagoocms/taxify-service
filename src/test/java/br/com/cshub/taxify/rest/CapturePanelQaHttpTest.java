package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.NamedLocks;
import br.com.cshub.taxify.config.CapturePanelProperties;
import br.com.cshub.taxify.config.CapturePriorityProperties;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.FiscalXmlSyncScheduler;
import br.com.cshub.taxify.service.capture.CapturePriorityService;
import br.com.cshub.taxify.service.capture.CaptureStageRecorder;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA do painel da captura de XML (spec 2026-11-27) por HTTP real (servidor em porta aleatoria, cliente java.net.http),
 * complementando o {@link CapturePanelIntegrationTest} (MockMvc): 401 sem token, isolamento entre escritorios, perfis,
 * visao da plataforma sem CNPJ, chave desligada fechando as 3 rotas, situacoes calculadas nas janelas de 24 h e 3 h com
 * relogio fixo, posicao na fila e estimativas, lock nomeado liberado depois do sync-now (IS_FREE_LOCK), simultaneidade
 * (inclusive uma "outra instancia" segurando o lock do escritorio), efeito na fila do agendador, gravacao de falha sem
 * texto cru e sem derrubar a rodada (inclusive com a tabela de etapas indisponivel).
 *
 * <p>Dados ficticios (@exemplo.test, CNPJ valido ficticio), apagados no fim. So roda com DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxify.platform.admin-logins=qa.cp.csadmin",
        "taxify.platform.firm-id=" + CapturePanelQaHttpTest.PLATFORM_FIRM,
        "taxify.capture-panel.platform-enabled=true",
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=true", "fiscal-xml.sync.cron=-", "fiscal-xml.sync.batch-size=2",
        "fiscal-xml.sync.reference-date=2026-11-27",
        "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "sefaz.dist.ufs=PE", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
class CapturePanelQaHttpTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-0000000c0de2";
    private static final String CSADMIN = "qa.cp.csadmin";
    private static final String PASSWORD = "SenhaDeTeste2026";
    private static final Random RANDOM = new Random();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        MutableClock qaPanelClock() {
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
    private MutableClock clock;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CapturePanelProperties panelProperties;
    @Autowired
    private CapturePriorityProperties priorityProperties;
    @Autowired
    private FiscalXmlSyncProperties syncProperties;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private FiscalXmlSyncControlRepository controlRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private FirmAccessService firmAccessService;
    @Autowired
    private CaptureStageRecorder stageRecorder;
    @Autowired
    private CapturePriorityService priorityService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> firms = new CopyOnWriteArrayList<>();
    private final String prefix = "qacp" + Integer.toHexString(RANDOM.nextInt(0xfffff)) + ".";
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    private String firmA;
    private String firmB;
    private String firmC;
    private String planSieg;
    private String x;
    private String y;
    private String z;
    private String z2;
    private String w;
    private String c1;

    /** Resposta HTTP: status, corpo e cabecalhos. */
    record Resp(int status, String raw, java.net.http.HttpHeaders headers, ObjectMapper mapper) {
        JsonNode body() {
            return mapper.readTree(raw);
        }

        String code() {
            JsonNode body = body();
            return body.has("code") ? body.get("code").asText() : null;
        }
    }

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        clock.set(NOW);
        panelProperties.setPlatformEnabled(true);
        priorityProperties.setEnabled(true);
        priorityProperties.setSlotsPerTick(1);
        syncProperties.setEnabled(true);
        syncProperties.setCron("0 0/5 * * * *");
        syncProperties.setBatchSize(2);
        removeFirm(PLATFORM_FIRM);
        logs.list.clear();
        logs.start();
        rootLogger.addAppender(logs);

        rawFirm(PLATFORM_FIRM, "CS Hub (teste QA)", null);
        user(PLATFORM_FIRM, CSADMIN, "ADMIN", null);

        firmA = firm("Escritorio Alfa", "ACTIVE");
        firmB = firm("Escritorio Beta", "BLOCKED");
        firmC = firm("Escritorio Gama", "ACTIVE");
        planSieg = plan(firmA, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        String root = digits(8);
        x = company(firmA, "Textil Exemplo Ltda", cnpj(digits(8), "0001"), "PE", planSieg);
        y = company(firmA, "Confeccao Modelo ME", cnpj(digits(8), "0001"), "PE", planSieg);
        z = company(firmA, "Comercio Teste Ltda", cnpj(root, "0001"), "PE", planSieg);
        z2 = company(firmA, "Comercio Teste Ltda", cnpj(root, "0002"), "PE", planSieg);
        w = company(firmB, "Industria Ficticia SA", cnpj(digits(8), "0001"), "PE",
                plan(firmB, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION));
        c1 = company(firmC, "Gamma Servicos Ltda", cnpj(digits(8), "0001"), "PE",
                plan(firmC, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION));
        String carla = user(firmA, "carla.admin", "ADMIN", null);
        String ana = user(firmA, "ana.fiscal", "EMPLOYEE", "FISCAL");
        user(firmA, "davi.gestao", "EMPLOYEE", "MANAGEMENT");
        user(firmA, "paulo.contab", "EMPLOYEE", "ACCOUNTING");
        user(firmA, "fabio.financ", "EMPLOYEE", "FINANCIAL");
        user(firmB, "admin.beta", "ADMIN", null);
        user(firmC, "admin.gama", "ADMIN", null);
        portfolio(ana, x);
        portfolio(ana, y);
        assertThat(carla).isNotNull();
        // o escritorio Gama entra na fila global: tentado ha 1 min e em dia, para nao passar na frente dos testes de fila
        control(c1, NOW.minusMinutes(1), 1, 1, 1, 1, NOW.minusHours(1), TODAY);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        panelProperties.setPlatformEnabled(true);
        priorityProperties.setEnabled(true);
        priorityProperties.setSlotsPerTick(1);
        priorityProperties.setPerFirmPerDay(10);
        priorityProperties.setMaxOpenPerFirm(5);
        syncProperties.setBatchSize(2);
        syncProperties.setEnabled(true);
        // se um teste interrompido deixou a tabela de etapas renomeada, restaura
        Integer renamed = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name = 'capture_stage_status_qa_off'", Integer.class);
        if (renamed != null && renamed > 0) {
            jdbc.execute("RENAME TABLE capture_stage_status_qa_off TO capture_stage_status");
        }
        for (String firm : firms) {
            removeFirm(firm);
        }
        firms.clear();
        removeFirm(PLATFORM_FIRM);
    }

    // ================================================================== 401: sem token

    /** Seguranca basica: nenhuma rota nova responde sem token (401/403), nem com token invalido. */
    @Test
    void everyRouteWithoutATokenIsRejected() throws Exception {
        List<String[]> routes = List.of(
                new String[]{"GET", "/capture-panel"},
                new String[]{"GET", "/capture-panel/companies/" + x},
                new String[]{"POST", "/capture-panel/companies/" + x + "/sync-now"},
                new String[]{"GET", "/platform/capture-panel"},
                new String[]{"GET", "/platform/capture-panel/companies/" + x},
                new String[]{"POST", "/platform/capture-panel/companies/" + x + "/sync-now"},
                new String[]{"GET", "/fiscal-xml-sync-control"});
        for (String[] route : routes) {
            assertThat(call(route[0], route[1], null).status()).as("sem token: " + route[0] + " " + route[1]).isEqualTo(401);
            assertThat(call(route[0], route[1], "isto.nao.e.um.jwt").status()).as("token invalido: " + route[1]).isIn(401, 403);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE accounting_firm_id = ?",
                Integer.class, firmA)).as("o POST sem token nao gravou pedido").isZero();
    }

    // ================================================================== isolamento

    /** CA-CP-01, CT-CP-38 a 40 por HTTP real: outro escritorio 404, fora da carteira 404, MANAGEMENT 403. */
    @Test
    void isolationBetweenFirmsAndProfilesOverRealHttp() throws Exception {
        control(x, NOW.minusHours(1), 1, 1, 1, 1, NOW.minusHours(2), TODAY);
        String carla = token("carla.admin");
        String ana = token("ana.fiscal");
        String gama = token("admin.gama");

        // ADMIN de outro escritorio ATIVO (o bloqueado seria 403 do somente leitura, mascarando o 404)
        assertThat(call("GET", "/capture-panel/companies/" + x, gama).status()).isEqualTo(404);
        assertThat(call("POST", "/capture-panel/companies/" + x + "/sync-now", gama).status()).isEqualTo(404);
        assertThat(call("GET", "/capture-panel/companies/" + c1, carla).status()).isEqualTo(404);
        assertThat(call("POST", "/capture-panel/companies/" + c1 + "/sync-now", carla).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE company_id IN (?, ?)", Integer.class, x, c1))
                .as("o 404 nao grava pedido").isZero();
        // id inexistente e id mal formado
        assertThat(call("GET", "/capture-panel/companies/" + UUID.randomUUID(), carla).status()).isEqualTo(404);
        assertThat(call("GET", "/capture-panel/companies/nao-e-uuid", carla).status()).isEqualTo(400);

        // a lista de cada escritorio so tem as proprias empresas; firmId em query na rota do escritorio e ignorado
        assertThat(ids(call("GET", "/capture-panel", gama).body())).containsExactly(c1);
        JsonNode withForeignFirmId = call("GET", "/capture-panel?firmId=" + firmC + "&size=100", carla).body();
        assertThat(ids(withForeignFirmId)).containsExactlyInAnyOrder(x, y, z, z2);
        assertThat(withForeignFirmId.toString()).doesNotContain(c1).doesNotContain("Gamma").doesNotContain(firmC);

        // FISCAL: so a carteira; detalhe fora da carteira 404; sem botao (403); em empresa fora da carteira tambem
        assertThat(ids(call("GET", "/capture-panel?size=100", ana).body())).containsExactlyInAnyOrder(x, y);
        assertThat(call("GET", "/capture-panel/companies/" + x, ana).status()).isEqualTo(200);
        assertThat(call("GET", "/capture-panel/companies/" + z, ana).status()).isEqualTo(404);
        assertThat(call("POST", "/capture-panel/companies/" + x + "/sync-now", ana).status()).isEqualTo(403);
        assertThat(call("POST", "/capture-panel/companies/" + z + "/sync-now", ana).status()).as("403 ou 404, nunca 202").isIn(403, 404);
        // busca por nome de empresa fora da carteira nao vaza
        assertThat(ids(call("GET", "/capture-panel?busca=comercio", ana).body())).isEmpty();
        // metacaracteres de LIKE/SQL na busca: nada de 500 e nada de "todas as empresas"
        for (String busca : List.of("%25", "_", "%27%20OR%201%3D1%20--", "%5C")) {
            Resp r = call("GET", "/capture-panel?busca=" + busca, ana);
            assertThat(r.status()).as("busca=" + busca).isEqualTo(200);
            assertThat(ids(r.body())).as("busca=" + busca).isSubsetOf(x, y);
        }

        // perfis sem INVOICES_SYNC: 403 em tudo
        for (String login : List.of("davi.gestao", "paulo.contab", "fabio.financ")) {
            String t = token(login);
            assertThat(call("GET", "/capture-panel", t).status()).as(login).isEqualTo(403);
            assertThat(call("GET", "/capture-panel/companies/" + x, t).status()).as(login).isEqualTo(403);
            assertThat(call("POST", "/capture-panel/companies/" + x + "/sync-now", t).status()).as(login).isEqualTo(403);
        }

        // ADMIN e FISCAL nao entram na plataforma; o ADMIN do escritorio tampouco
        for (String t : List.of(carla, ana, gama)) {
            assertThat(call("GET", "/platform/capture-panel", t).status()).isEqualTo(403);
            assertThat(call("GET", "/platform/capture-panel/companies/" + x, t).status()).isEqualTo(403);
            assertThat(call("POST", "/platform/capture-panel/companies/" + x + "/sync-now", t).status()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE accounting_firm_id IN (?, ?, ?)",
                Integer.class, firmA, firmB, firmC)).isZero();
    }

    // ================================================================== plataforma sem CNPJ

    /** CA-CP-02 / CP-RN-02 por HTTP real: nenhum CNPJ (inteiro, formatado, raiz+filial) nem last_detail, com log de acesso. */
    @Test
    void platformViewNeverBringsTheFullCnpjAndLogsTheAccess() throws Exception {
        certificate(x, NOW.plusDays(300));
        sefazControl(x, NOW.minusHours(1), "ERRO", "detalhe sigiloso 99887766000155 token=zzz999");
        String sefazPlan = plan(firmA, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION, PlanServiceEnum.SEFAZ_INTEGRATION);
        jdbc.update("UPDATE subscriptions SET plan_id = ? WHERE company_id = ?", sefazPlan, x);
        String csadmin = token(CSADMIN);

        List<String> documents = jdbc.queryForList("SELECT d.number FROM documents d JOIN companies c ON c.document_id = d.id"
                + " WHERE c.accounting_firm_id IN (?, ?, ?)", String.class, firmA, firmB, firmC);
        assertThat(documents).hasSize(6);

        String list = call("GET", "/platform/capture-panel?size=100", csadmin).raw();
        String detail = call("GET", "/platform/capture-panel/companies/" + x, csadmin).raw();
        String sync = call("POST", "/platform/capture-panel/companies/" + x + "/sync-now", csadmin).raw();
        for (String body : List.of(list, detail, sync)) {
            for (String doc : documents) {
                assertThat(body).as("CNPJ cru").doesNotContain(doc);
                String masked = doc.substring(0, 2) + "." + doc.substring(2, 5) + "." + doc.substring(5, 8) + "/" + doc.substring(8, 12) + "-" + doc.substring(12);
                assertThat(body).as("CNPJ formatado").doesNotContain(masked);
                assertThat(body).as("raiz + estabelecimento").doesNotContain(doc.substring(0, 8) + "/" + doc.substring(8, 12));
            }
            assertThat(body).doesNotContain("99887766000155").doesNotContain("zzz999").doesNotContain("detalhe sigiloso");
            assertThat(body.toLowerCase()).doesNotContain("cnpj\"").doesNotContain("last_detail").doesNotContain("lastdetail");
        }
        JsonNode row = null;
        for (JsonNode candidate : call("GET", "/platform/capture-panel?size=100", csadmin).body().at("/page/content")) {
            if (candidate.get("companyId").asText().equals(z2)) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("establishment").asText()).isEqualTo("0002");
        assertThat(row.get("firmName").asText()).startsWith("Escritorio Alfa");
        assertThat(row.get("companyName").asText()).isEqualTo("Comercio Teste Ltda");

        List<String> viewed = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("capture.panel.viewed")).toList();
        assertThat(viewed).isNotEmpty().allSatisfy(message -> assertThat(message).startsWith("capture.panel.viewed scope=platform by="));
        assertThat(viewed).anySatisfy(message -> assertThat(message).endsWith("firm=all"));
        call("GET", "/platform/capture-panel?firmId=" + firmA, csadmin);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anySatisfy(message -> assertThat(message).endsWith("firm=" + firmA));
        // o log da feature nao leva nome de empresa nem CNPJ
        for (ILoggingEvent event : logs.list) {
            String message = event.getFormattedMessage();
            if (message.startsWith("capture.")) {
                assertThat(message).doesNotContain("Textil").doesNotContain("Comercio").doesNotContain("Gamma");
                for (String doc : documents) {
                    assertThat(message).doesNotContain(doc);
                }
            }
        }
        // o detalhe da visao do escritorio tambem nao traz o CNPJ
        String firmDetail = call("GET", "/capture-panel/companies/" + x, token("carla.admin")).raw();
        for (String doc : documents) {
            assertThat(firmDetail).doesNotContain(doc);
        }
    }

    /** CT-CP-69: a chave em false fecha as 3 rotas da plataforma (404), sem afetar a visao do escritorio. */
    @Test
    void platformSwitchOffClosesTheThreeRoutes() throws Exception {
        panelProperties.setPlatformEnabled(false);
        String csadmin = token(CSADMIN);
        assertThat(call("GET", "/platform/capture-panel", csadmin).status()).isEqualTo(404);
        assertThat(call("GET", "/platform/capture-panel?firmId=" + firmA + "&size=10", csadmin).status()).isEqualTo(404);
        assertThat(call("GET", "/platform/capture-panel/companies/" + x, csadmin).status()).isEqualTo(404);
        assertThat(call("POST", "/platform/capture-panel/companies/" + x + "/sync-now", csadmin).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE company_id = ?", Integer.class, x))
                .as("desligada nao grava pedido").isZero();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)).noneMatch(m -> m.startsWith("capture.panel.viewed"));
        // quem nao e admin da plataforma continua com 403 (nao vaza se a chave esta ligada)
        assertThat(call("GET", "/platform/capture-panel", token("carla.admin")).status()).isEqualTo(403);
        // a visao do escritorio nao e afetada
        assertThat(call("GET", "/capture-panel", token("carla.admin")).status()).isEqualTo(200);
        assertThat(call("POST", "/capture-panel/companies/" + x + "/sync-now", token("carla.admin")).status()).isEqualTo(202);
        // religada, as 3 rotas voltam
        panelProperties.setPlatformEnabled(true);
        assertThat(call("GET", "/platform/capture-panel", csadmin).status()).isEqualTo(200);
        assertThat(call("GET", "/platform/capture-panel/companies/" + x, csadmin).status()).isEqualTo(200);
        assertThat(call("POST", "/platform/capture-panel/companies/" + y + "/sync-now", csadmin).status()).isEqualTo(202);
    }

    // ================================================================== situacoes calculadas

    /** CP-RN-06 e 07: as sete situacoes e as fronteiras das janelas (24 h SIEG, 3 h SEFAZ/ADN) com o relogio fixo. */
    @Test
    void situationsAndWindowBoundaries() throws Exception {
        String full = plan(firmA, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION,
                PlanServiceEnum.SEFAZ_INTEGRATION, PlanServiceEnum.NFSE_NATIONAL_INTEGRATION);
        String noImport = plan(firmA, PlanServiceEnum.SIEG_INTEGRATION);
        jdbc.update("UPDATE subscriptions SET plan_id = ? WHERE company_id IN (?, ?)", full, x, y);
        String gate = company(firmA, "Fronteira Exata Ltda", cnpj(digits(8), "0001"), "PE", planSieg);
        String late = company(firmA, "Fronteira Atraso Ltda", cnpj(digits(8), "0001"), "PE", planSieg);
        String out = company(firmA, "Sem Plano Ltda", cnpj(digits(8), "0001"), "PE", noImport);
        String never = company(firmA, "Nunca Tentada Ltda", cnpj(digits(8), "0001"), "PE", planSieg);

        LocalDateTime at24 = NOW.minusHours(24);
        control(gate, NOW.minusMinutes(5), 10, 10, 2, 2, at24, TODAY);                       // exatamente 24 h
        control(late, NOW.minusMinutes(5), 10, 10, 2, 2, at24.minusMinutes(1), TODAY);       // 24 h 01
        control(z, NOW.minusMinutes(5), 10, 10, 2, 2, NOW.minusHours(23).minusMinutes(59), TODAY);   // 23 h 59 -> em dia
        control(z2, NOW.minusMinutes(5), 10, 10, 2, 2, NOW.minusHours(1), TODAY.minusDays(1));        // varridos ontem -> em dia
        control(w, null, 0, 0, 0, 0, NOW, TODAY);                                             // bloqueado
        control(never, null, 0, 0, 0, 0, NOW, TODAY);
        // x: eventos varridos ha 2 dias -> atrasada; y: pendente de verdade
        control(x, NOW.minusMinutes(5), 10, 10, 2, 2, NOW.minusHours(1), TODAY.minusDays(2));
        control(y, NOW.minusMinutes(5), 200, 180, 2, 2, NOW.minusHours(1), TODAY);

        String carla = token("carla.admin");
        JsonNode view = call("GET", "/capture-panel?size=100", carla).body();
        assertThat(sieg(view, gate)).as("exatamente 24 h = dentro da janela").isEqualTo("EM_DIA");
        assertThat(sieg(view, late)).as("24 h 01").isEqualTo("ATRASADA");
        assertThat(sieg(view, z)).as("23 h 59").isEqualTo("EM_DIA");
        assertThat(sieg(view, z2)).as("eventos de ontem").isEqualTo("EM_DIA");
        assertThat(sieg(view, x)).as("eventos de anteontem").isEqualTo("ATRASADA");
        assertThat(sieg(view, y)).isEqualTo("PENDENTE");
        assertThat(sieg(view, out)).isEqualTo("FORA_DO_PLANO");
        assertThat(row(view, out).get("status").asText()).isEqualTo("FORA_DO_PLANO");
        assertThat(sieg(view, never)).isEqualTo("NUNCA_TENTADA");
        assertThat(row(view, late).get("statusReason").asText()).isEqualTo("Sem verificação completa há 24 h");

        // resumo: bate com a lista (mesmo escopo, sem filtros)
        Map<String, Integer> counted = new java.util.HashMap<>();
        for (JsonNode r : view.at("/page/content")) {
            counted.merge(r.get("status").asText(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counted.entrySet()) {
            assertThat(view.at("/summary/byStatus/" + e.getKey()).asInt()).as("resumo " + e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(view.at("/summary/byStatus/BLOQUEADA").asInt()).isZero();

        // SEFAZ e ADN: 3 h
        certificate(x, NOW.plusDays(400));
        sefazControl(x, NOW.minusHours(3), "OK", null);
        adnControl(x, NOW.minusHours(3).minusMinutes(1), "OK");
        JsonNode xRow = row(call("GET", "/capture-panel?size=100", carla).body(), x);
        assertThat(xRow.at("/channels/SEFAZ/status").asText()).as("exatamente 3 h").isEqualTo("EM_DIA");
        assertThat(xRow.at("/channels/ADN/status").asText()).as("3 h 01").isEqualTo("ATRASADA");
        sefazControl(x, NOW.minusHours(2).minusMinutes(59), "SEM_MOVIMENTO", null);
        adnControl(x, NOW.minusMinutes(10), "ERRO");
        xRow = row(call("GET", "/capture-panel?size=100", carla).body(), x);
        assertThat(xRow.at("/channels/SEFAZ/status").asText()).isEqualTo("EM_DIA");
        assertThat(xRow.at("/channels/ADN/status").asText()).isEqualTo("COM_ERRO");
        assertThat(xRow.at("/channels/ADN/failureCode").asText()).isEqualTo("LEGACY_ERROR");
        assertThat(xRow.get("status").asText()).as("a pior situacao entre os canais").isEqualTo("COM_ERRO");
        assertThat(call("GET", "/capture-panel/companies/" + x, carla).raw()).doesNotContain("last_detail");

        // BLOQUEADA nos tres canais para o escritorio bloqueado, na visao da plataforma
        JsonNode platform = call("GET", "/platform/capture-panel?firmId=" + firmB, token(CSADMIN)).body();
        assertThat(row(platform, w).get("status").asText()).isEqualTo("BLOQUEADA");
        assertThat(platform.at("/summary/byStatus/BLOQUEADA").asInt()).isEqualTo(1);

        // COM_ERRO do SIEG gravado pela etapa (sem chamada real), depois inativa some do resumo
        stageRecorder.failure(UUID.fromString(never), br.com.cshub.taxify.enumerated.CaptureStage.SIEG_RECEIVED,
                br.com.cshub.taxify.enumerated.CaptureFailureCode.SIEG_AUTH, "401");
        jdbc.update("UPDATE fiscal_xml_sync_control SET last_attempt_at = ? WHERE company_id = ?", Timestamp.valueOf(NOW.minusMinutes(1)), never);
        JsonNode afterError = call("GET", "/capture-panel?size=100", carla).body();
        assertThat(sieg(afterError, never)).isEqualTo("COM_ERRO");
        assertThat(row(afterError, never).get("statusReason").asText())
                .isEqualTo("O SIEG recusou a chave da API do escritório (HTTP 401). Confira a chave em Integrações.");
    }

    // ================================================================== fila e estimativas

    /** CP-RN-12 e CP-RN-14: posicao a partir de 0 na ordem do agendador, estimativa por rodadas, numeros da fila so da plataforma. */
    @Test
    void queuePositionEstimatesAndFirmSeesNoQueueNumbers() throws Exception {
        // so as empresas deste teste competem: o escritorio Alfa tem 4 + 4 novas, e os outros escritorios 1 cada (Gama, sem controle -> nunca tentada)
        List<String> extra = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            extra.add(company(firmA, "Fila Extra " + i + " Ltda", cnpj(digits(8), "0001"), "PE", planSieg));
        }
        List<String> order = new ArrayList<>(List.of(x, y, z, z2));
        order.addAll(extra);
        // a mais antiga primeiro: ordem = ordem da lista, de 09:00 ate 09:35 (de 5 em 5)
        for (int i = 0; i < order.size(); i++) {
            control(order.get(i), NOW.withHour(9).withMinute(5 * i), 1, 1, 1, 1, NOW.minusHours(1), TODAY);
        }
        // as empresas dos outros escritorios entram depois (tentadas ha 10 min), para nao passarem na frente
        control(c1, NOW.minusMinutes(10), 1, 1, 1, 1, NOW.minusHours(1), TODAY);
        control(w, NOW.minusMinutes(10), 1, 1, 1, 1, NOW.minusHours(1), TODAY);
        // qualquer linha de controle que sobrou de outro teste/execucao entra na fila global: mede a posicao relativa
        String csadmin = token(CSADMIN);
        JsonNode platform = call("GET", "/platform/capture-panel?firmId=" + firmA + "&size=100&sort=nextAttempt", csadmin).body();
        List<Integer> positions = new ArrayList<>();
        for (String company : order) {
            positions.add(row(platform, company).at("/channels/SIEG/queuePosition").asInt());
        }
        for (int i = 1; i < positions.size(); i++) {
            assertThat(positions.get(i)).as("posicao %d", i).isEqualTo(positions.get(i - 1) + 1);
        }
        int base = positions.getFirst();
        assertThat(base).as("0 e a proxima a ser atendida (a API comeca em 0)").isGreaterThanOrEqualTo(0);

        // a plataforma ve a posicao e nao a estimativa; o ADMIN do escritorio ve a posicao e a estimativa
        assertThat(row(platform, x).at("/channels/SIEG/nextAttemptEstimate").isNull()).isTrue();
        JsonNode firmView = call("GET", "/capture-panel?size=100", token("carla.admin")).body();
        for (int i = 0; i < order.size(); i++) {
            JsonNode sieg = row(firmView, order.get(i)).at("/channels/SIEG");
            int position = sieg.get("queuePosition").asInt();
            assertThat(position).isEqualTo(positions.get(i));
            // vagas regulares = lote 2 (sem pedido aberto): 10:05 + floor(posicao / 2) x 5 min
            LocalDateTime expected = LocalDateTime.of(2026, 11, 27, 10, 5).plusMinutes(5L * (position / 2));
            assertThat(sieg.get("nextAttemptEstimate").asText()).as("estimativa da posicao %d", position)
                    .isEqualTo(expected + ":00-03:00");
        }

        // o ADMIN do escritorio nao recebe os numeros da fila da plataforma
        JsonNode firmQueue = firmView.get("queue");
        assertThat(firmQueue.get("observedLapMinutes").isNull()).isTrue();
        assertThat(firmQueue.get("theoreticalLapMinutes").isNull()).isTrue();
        assertThat(firmQueue.get("stalledSince").isNull()).isTrue();
        assertThat(firmQueue.get("health").asText()).isIn("RUNNING", "STALLED", "DISABLED");
        JsonNode platformQueue = platform.get("queue");
        assertThat(platformQueue.get("theoreticalLapMinutes").asLong()).as("ceil(N/2) x 5").isGreaterThan(0);
        assertThat(platformQueue.get("nextTickAt").asText()).isEqualTo("2026-11-27T10:05:00-03:00");

        // com 1 pedido aberto, a vaga regular cai para 1: a estimativa de quem esta atras dobra o passo
        accepted("/capture-panel/companies/" + z2 + "/sync-now", token("carla.admin"));
        JsonNode withOpen = call("GET", "/capture-panel?size=100", token("carla.admin")).body();
        JsonNode last = row(withOpen, extra.getLast()).at("/channels/SIEG");
        int lastPosition = last.get("queuePosition").asInt();
        LocalDateTime expectedLast = LocalDateTime.of(2026, 11, 27, 10, 5).plusMinutes(5L * lastPosition);
        assertThat(last.get("nextAttemptEstimate").asText()).as("1 vaga regular com 1 pedido aberto (CP-RN-12)")
                .isEqualTo(expectedLast + ":00-03:00");
    }

    // ================================================================== lock nomeado e simultaneidade

    /** Sugestao do reviewer: apos o sync-now (qualquer desfecho) o lock capprio: do escritorio esta livre. */
    @Test
    void theFirmLockIsFreeAfterEverySyncNowOutcome() throws Exception {
        String lock = NamedLocks.name("capprio:", firmA);
        String carla = token("carla.admin");
        assertThat(isFree(lock)).isTrue();

        Resp created = call("POST", "/capture-panel/companies/" + x + "/sync-now", carla);
        assertThat(created.status()).isEqualTo(202);
        assertThat(isFree(lock)).as("depois do 202 criado").isTrue();
        assertThat(isUsed(lock)).isNull();

        Resp again = call("POST", "/capture-panel/companies/" + x + "/sync-now", carla);
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.body().get("requestId").asText()).isEqualTo(created.body().get("requestId").asText());
        assertThat(isFree(lock)).as("depois do 202 idempotente").isTrue();

        priorityProperties.setMaxOpenPerFirm(1);
        Resp open = call("POST", "/capture-panel/companies/" + y + "/sync-now", carla);
        assertThat(open.status()).isEqualTo(429);
        assertThat(isFree(lock)).as("depois do 429 de abertos (excecao dentro da transacao)").isTrue();
        priorityProperties.setMaxOpenPerFirm(5);

        // cooldown: pedido servido ha pouco
        jdbc.update("UPDATE capture_priority_requests SET status = 'SERVED', open_key = NULL, served_at = ? WHERE company_id = ?",
                Timestamp.valueOf(NOW.minusMinutes(10)), x);
        Resp cooldown = call("POST", "/capture-panel/companies/" + x + "/sync-now", carla);
        assertThat(cooldown.status()).isEqualTo(429);
        assertThat(cooldown.code()).isEqualTo("error.capture.priority.cooldown");
        assertThat(isFree(lock)).as("depois do 429 de cooldown").isTrue();

        priorityProperties.setPerFirmPerDay(1);
        Resp daily = call("POST", "/capture-panel/companies/" + z + "/sync-now", carla);
        assertThat(daily.status()).isEqualTo(429);
        assertThat(isFree(lock)).as("depois do 429 diario").isTrue();

        // as rotas que recusam antes do lock nem chegam a pega-lo
        assertThat(call("POST", "/capture-panel/companies/" + c1 + "/sync-now", carla).status()).isEqualTo(404);
        assertThat(isFree(lock)).isTrue();
        assertThat(isFree(NamedLocks.name("capprio:", firmC))).isTrue();
        // plataforma
        priorityProperties.setPerFirmPerDay(10);
        assertThat(call("POST", "/platform/capture-panel/companies/" + y + "/sync-now", token(CSADMIN)).status()).isEqualTo(202);
        assertThat(isFree(lock)).as("depois do pedido da plataforma").isTrue();

        // muitos pedidos em sequencia nao deixam conexao do pool presa com lock
        for (int i = 0; i < 30; i++) {
            call("POST", "/capture-panel/companies/" + y + "/sync-now", carla);
        }
        assertThat(isFree(lock)).isTrue();
    }

    /** CA-CP-18 / CT-CP-44 por HTTP real: 12 cliques ao mesmo tempo na mesma empresa = 1 pedido, 12 respostas 202 iguais. */
    @Test
    void manySimultaneousClicksOpenOneRequest() throws Exception {
        String carla = token("carla.admin");
        int clients = 12;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(clients);
        try {
            List<CompletableFuture<Resp>> calls = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return call("POST", "/capture-panel/companies/" + z + "/sync-now", carla);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, executor));
            }
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (CompletableFuture<Resp> future : calls) {
                Resp r = future.get();
                assertThat(r.status()).as(r.raw()).isEqualTo(202);
                ids.add(r.body().get("requestId").asText());
            }
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE company_id = ?", Integer.class, z)).isEqualTo(1);
            assertThat(isFree(NamedLocks.name("capprio:", firmA))).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * "Duas instancias": uma sessao MySQL propria (como o outro processo) segura o lock capprio: do escritorio. A
     * requisicao espera ate 5 s e, se o lock nao sai, responde 503 sem gravar; se sai a tempo, entra e respeita o pedido
     * que a outra instancia abriu nesse meio tempo (202 com o MESMO id, nada contado a mais).
     */
    @Test
    void anotherInstanceHoldingTheFirmLock() throws Exception {
        String lock = NamedLocks.name("capprio:", firmA);
        String carla = token("carla.admin");
        try (Connection other = rawConnection()) {
            assertThat(scalar(other, "SELECT GET_LOCK('" + lock + "', 1)")).isEqualTo(1L);
            long started = System.currentTimeMillis();
            Resp busy = call("POST", "/capture-panel/companies/" + x + "/sync-now", carla);
            long waited = System.currentTimeMillis() - started;
            assertThat(busy.status()).as(busy.raw()).isEqualTo(503);
            assertThat(busy.code()).isEqualTo("error.capture.priority.unavailable");
            assertThat(waited).as("espera o lock por ~5 s e nao trava").isBetween(4_000L, 15_000L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE company_id = ?", Integer.class, x)).isZero();
            assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)).anyMatch(m -> m.equals("capture.priority.busy firm=" + firmA));

            // a outra instancia segura o lock por 1,5 s, abre o pedido da empresa e solta; a nossa requisicao entra depois
            String otherRequest = UUID.randomUUID().toString();
            var delayed = CompletableFuture.supplyAsync(() -> {
                try {
                    return call("POST", "/capture-panel/companies/" + y + "/sync-now", carla);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(1_500);
            jdbc.update("INSERT INTO capture_priority_requests (id, company_id, accounting_firm_id, requested_by, requested_scope,"
                            + " requested_at, status, open_key, deleted, created_by, created_date) VALUES (?, ?, ?, ?, 'FIRM', ?, 'PENDING', ?,"
                            + " 0, 'qa', ?)", otherRequest, y, firmA, jdbc.queryForObject("SELECT id FROM users WHERE login = ?", String.class,
                            prefix + "carla.admin"), Timestamp.valueOf(NOW), y, Timestamp.valueOf(NOW));
            assertThat(scalar(other, "SELECT RELEASE_LOCK('" + lock + "')")).isEqualTo(1L);
            Resp resumed = delayed.get();
            assertThat(resumed.status()).as(resumed.raw()).isEqualTo(202);
            assertThat(resumed.body().get("requestId").asText()).as("a mesma resposta da outra instancia").isEqualTo(otherRequest);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE company_id = ?", Integer.class, y)).isEqualTo(1);
        }
        assertThat(isFree(lock)).isTrue();
    }

    // ================================================================== efeito na fila do agendador

    /** CP-RN-15 com lote 2: no maximo 1 vaga para pedidos (mesmo com slots=5), a outra e da fila normal; os demais seguem PENDING. */
    @Test
    void priorityTakesAtMostOneSlotOfTwoAndTheRestWaits() throws Exception {
        priorityProperties.setSlotsPerTick(5);
        List<String> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            others.add(company(firmA, "Normal " + i + " Ltda", cnpj(digits(8), "0001"), "PE", planSieg));
        }
        control(x, NOW.minusHours(2), 1, 1, 1, 1, NOW.minusHours(2), TODAY);
        control(y, NOW.minusHours(2), 1, 1, 1, 1, NOW.minusHours(2), TODAY);
        control(z, NOW.minusHours(2), 1, 1, 1, 1, NOW.minusHours(2), TODAY);
        for (int i = 0; i < others.size(); i++) {
            control(others.get(i), NOW.minusHours(10 - i), 5, 3, 1, 1, NOW.minusHours(3), TODAY); // pendente: a rodada chama o provedor
        }
        control(z2, NOW.minusMinutes(1), 1, 1, 1, 1, NOW.minusHours(1), TODAY);
        String carla = token("carla.admin");
        List<String> ids = new ArrayList<>();
        for (String company : List.of(x, y, z)) {
            clock.set(NOW.plusSeconds(ids.size()));
            ids.add(accepted("/capture-panel/companies/" + company + "/sync-now", carla).get("requestId").asText());
        }
        clock.set(NOW.plusMinutes(5));
        Recording provider = new Recording();
        scheduler(provider, 2).syncAllCompanies();
        assertThat(provider.received()).as("2 empresas por rodada").hasSize(2);
        assertThat(provider.received().getFirst()).as("o pedido mais antigo na frente").isEqualTo(x);
        assertThat(provider.received().get(1)).as("a outra vaga e da fila normal: a mais antiga (others[0])").isEqualTo(others.getFirst());
        assertThat(status(ids.get(0))).isEqualTo("SERVED");
        assertThat(status(ids.get(1))).isEqualTo("PENDING");
        assertThat(status(ids.get(2))).isEqualTo("PENDING");
        // a contagem forcada vale para os dois lados do pedido
        assertThat(provider.requests.stream().filter(r -> r.companyId().toString().equals(x)).toList())
                .hasSize(2).allSatisfy(r -> assertThat(r.knownPending()).isFalse());
        // a empresa atendida vai para o fim da fila normal
        assertThat(jdbc.queryForObject("SELECT last_attempt_at FROM fiscal_xml_sync_control WHERE company_id = ?", Timestamp.class, x))
                .isEqualTo(Timestamp.valueOf(NOW.plusMinutes(5)));
        // proxima rodada: o segundo pedido
        clock.set(NOW.plusMinutes(10));
        Recording second = new Recording();
        scheduler(second, 2).syncAllCompanies();
        assertThat(second.received().getFirst()).isEqualTo(y);
        assertThat(status(ids.get(1))).isEqualTo("SERVED");
        assertThat(status(ids.get(2))).isEqualTo("PENDING");
    }

    // ================================================================== gravacao de falha

    /**
     * CP-RN-11: o que quer que o provedor lance (inclusive mensagem com CNPJ e token), o banco so tem codigo da lista fechada e
     * status de ate 4 digitos; a resposta do painel e os logs da feature nao os trazem.
     */
    @Test
    void failureRecordingKeepsOnlyClosedListCodes() throws Exception {
        String carla = token("carla.admin");
        String secret = "12345678000190 token=abc123 ApiKey=ZZZ-SEGREDO";
        List<RuntimeException> errors = List.of(
                new SiegIntegrationException("error.sieg.integration.failed", secret, 400),
                new SiegIntegrationException("error.sieg.integration.failed", secret, 401),
                new SiegIntegrationException("error.sieg.integration.failed", secret, 429),
                new SiegIntegrationException("error.sieg.integration.failed", secret, 503),
                new SiegIntegrationException("error.sieg.api.key.missing", secret, null),
                new IllegalStateException(secret),
                new RuntimeException(secret, new java.net.SocketTimeoutException(secret)),
                new RuntimeException(secret, new java.net.ConnectException(secret)));
        Set<String> allowedCodes = new HashSet<>();
        for (br.com.cshub.taxify.enumerated.CaptureFailureCode code : br.com.cshub.taxify.enumerated.CaptureFailureCode.values()) {
            allowedCodes.add(code.name());
        }
        int tick = 0;
        for (RuntimeException error : errors) {
            tick++;
            clock.set(NOW.plusMinutes(5L * tick));
            jdbc.update("DELETE FROM capture_stage_status WHERE company_id = ?", x);
            control(x, null, 0, 0, 0, 0, NOW.minusDays(2), TODAY.minusDays(3));
            for (String other : List.of(y, z, z2)) {
                control(other, clock.instant().atZone(java.time.ZoneId.of("America/Recife")).toLocalDateTime(), 1, 1, 1, 1,
                        NOW.plusMinutes(5L * tick), TODAY);
            }
            Recording provider = new Recording();
            provider.failWith = error;
            scheduler(provider, 1).syncAllCompanies();
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM capture_stage_status WHERE company_id = ?", x);
            assertThat(rows).as(error.toString()).hasSize(1);
            Map<String, Object> row = rows.getFirst();
            assertThat(row.get("last_outcome")).isEqualTo("FAILURE");
            assertThat(allowedCodes).contains((String) row.get("failure_code"));
            Object status = row.get("provider_status");
            assertThat(status == null || String.valueOf(status).matches("\\d{1,4}")).as("provider_status=" + status).isTrue();
            String dump = row.toString();
            assertThat(dump).doesNotContain("12345678000190").doesNotContain("abc123").doesNotContain("ZZZ-SEGREDO");
            String detail = call("GET", "/capture-panel/companies/" + x, carla).raw();
            assertThat(detail).doesNotContain("12345678000190").doesNotContain("abc123").doesNotContain("ZZZ-SEGREDO");
            assertThat(call("GET", "/capture-panel?size=100", carla).raw()).doesNotContain("12345678000190").doesNotContain("abc123");
        }
        // CT-CP-13 / CT-CP-15 (codigos esperados por tipo)
        assertThat(jdbc.queryForObject("SELECT failure_code FROM capture_stage_status WHERE company_id = ?", String.class, x)).isEqualTo("SIEG_CONNECTION");
        // logs da feature (recorder, classificador, painel, prioridade) nunca com o texto cru
        for (ILoggingEvent event : logs.list) {
            if (event.getLoggerName().startsWith("br.com.cshub.taxify.service.capture")) {
                assertThat(event.getFormattedMessage()).doesNotContain("12345678000190").doesNotContain("abc123").doesNotContain("ZZZ-SEGREDO");
                assertThat(event.getThrowableProxy()).as("o recorder nao loga pilha com a mensagem").isNull();
            }
        }
    }

    /**
     * CA-CP-24 / CT-CP-65 de ponta a ponta: com a tabela capture_stage_status indisponivel (renomeada so durante a rodada), as
     * notas/contadores seguem gravados e a rodada processa as duas empresas do lote; o log e o do recorder, so com ids.
     */
    @Test
    void theStageTableUnavailableNeverBreaksTheRound() throws Exception {
        control(x, NOW.minusHours(5), 5, 3, 1, 1, NOW.minusHours(3), TODAY); // pendente: a rodada chama o provedor
        control(y, NOW.minusHours(4), 5, 3, 1, 1, NOW.minusHours(3), TODAY);
        control(z, NOW.minusMinutes(1), 1, 1, 1, 1, NOW.minusHours(1), TODAY);
        control(z2, NOW.minusMinutes(1), 1, 1, 1, 1, NOW.minusHours(1), TODAY);
        clock.set(NOW.plusMinutes(5));
        Recording provider = new Recording();
        jdbc.execute("RENAME TABLE capture_stage_status TO capture_stage_status_qa_off");
        try {
            scheduler(provider, 2).syncAllCompanies();
        } finally {
            jdbc.execute("RENAME TABLE capture_stage_status_qa_off TO capture_stage_status");
        }
        assertThat(provider.received()).as("as duas empresas do lote foram capturadas").containsExactlyInAnyOrder(x, y);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_sync_control WHERE last_attempt_at = ?", Integer.class,
                Timestamp.valueOf(NOW.plusMinutes(5)))).isEqualTo(2);
        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("capture.stage.record.failed")).toList();
        assertThat(messages).isNotEmpty().allSatisfy(m -> assertThat(m).contains("company="));
        // falha do provedor + tabela fora: a excecao do provedor ainda e tratada como antes e a outra empresa roda
        clock.set(NOW.plusMinutes(10));
        control(x, NOW.minusHours(5), 5, 3, 1, 1, NOW.minusHours(3), TODAY.minusDays(1));
        control(y, NOW.minusHours(4), 5, 3, 1, 1, NOW.minusHours(3), TODAY.minusDays(1));
        Recording failing = new Recording();
        failing.failWith = new SiegIntegrationException("error.sieg.integration.failed", "x", 503);
        failing.only = Set.of(x);
        jdbc.execute("RENAME TABLE capture_stage_status TO capture_stage_status_qa_off");
        try {
            scheduler(failing, 2).syncAllCompanies();
        } finally {
            jdbc.execute("RENAME TABLE capture_stage_status_qa_off TO capture_stage_status");
        }
        assertThat(failing.received()).contains(x, y);
    }

    // ================================================================== endpoint obsoleto

    /** CA-CP-27 por HTTP real. */
    @Test
    void oldEndpointKeepsItsBodyAndGainsDeprecation() throws Exception {
        control(x, NOW.minusHours(1), 3, 3, 1, 1, NOW.minusHours(2), TODAY);
        Resp r = call("GET", "/fiscal-xml-sync-control", token("ana.fiscal"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().firstValue("Deprecation")).contains("true");
        assertThat(r.body().isArray()).isTrue();
        assertThat(call("GET", "/fiscal-xml-sync-control", token("davi.gestao")).status()).isEqualTo(200);
    }

    // ================================================================== helpers

    private String status(String requestId) {
        return jdbc.queryForObject("SELECT status FROM capture_priority_requests WHERE id = ?", String.class, requestId);
    }

    private static String sieg(JsonNode view, String company) {
        return row(view, company).at("/channels/SIEG/status").asText();
    }

    private boolean isFree(String lock) {
        Number free = jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Number.class, lock);
        return free != null && free.intValue() == 1;
    }

    private Number isUsed(String lock) {
        return jdbc.queryForObject("SELECT IS_USED_LOCK(?)", Number.class, lock);
    }

    private Connection rawConnection() throws Exception {
        String url = jdbc.execute((Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(url != null && url.startsWith("jdbc:mysql://localhost:"), "so MySQL local");
        return DriverManager.getConnection(url, System.getenv().getOrDefault("DB_USER", "root"),
                System.getenv().getOrDefault("DB_PASSWORD", "root"));
    }

    private static Long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private FiscalXmlSyncScheduler scheduler(FiscalXmlProvider provider, int batch) {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setEnabled(true);
        properties.setProvider("sieg");
        properties.setBatchSize(batch);
        properties.setReferenceDate(TODAY);
        return new FiscalXmlSyncScheduler(companyRepository, controlRepository, subscriptionRepository, Map.of("sieg", provider),
                properties, firmAccessService, null, clock, stageRecorder, priorityService);
    }

    /** Provedor simulado: nunca fala com o SIEG; registra cada pedido. */
    static class Recording implements FiscalXmlProvider {
        final List<FiscalXmlSyncRequest> requests = Collections.synchronizedList(new ArrayList<>());
        RuntimeException failWith;
        Set<String> only;

        @Override
        public FiscalXmlSyncResult sync(FiscalXmlSyncRequest request) {
            requests.add(request);
            if (failWith != null && (only == null || only.contains(request.companyId().toString()))) {
                throw failWith;
            }
            return new FiscalXmlSyncResult(0, 0, 0);
        }

        List<String> received() {
            return requests.stream().filter(r -> r.side() == FiscalXmlSide.RECEIVED).map(r -> r.companyId().toString()).toList();
        }
    }

    // ---- HTTP

    private Resp call(String method, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1" + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body(), response.headers(), json);
    }

    private JsonNode accepted(String path, String token) throws Exception {
        Resp r = call("POST", path, token);
        assertThat(r.status()).as(r.raw()).isEqualTo(202);
        return r.body();
    }

    private String token(String login) throws Exception {
        String full = CSADMIN.equals(login) ? login : prefix + login;
        String auth = Base64.getEncoder().encodeToString((full + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"auth\":\"" + auth + "\"}")).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("login " + login + ": " + response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asText();
    }

    private static List<String> ids(JsonNode panel) {
        List<String> ids = new ArrayList<>();
        panel.at("/page/content").forEach(row -> ids.add(row.get("companyId").asText()));
        return ids;
    }

    private static JsonNode row(JsonNode panel, String companyId) {
        for (JsonNode row : panel.at("/page/content")) {
            if (row.get("companyId").asText().equals(companyId)) {
                return row;
            }
        }
        throw new AssertionError("company " + companyId + " not in the page: " + ids(panel));
    }

    // ---- fixtures (ficticios)

    private String firm(String name, String status) {
        String id = UUID.randomUUID().toString();
        rawFirm(id, name + " " + prefix, status);
        firms.add(id);
        return id;
    }

    private void rawFirm(String id, String name, String status) {
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)", id, now, name);
        if (status != null) {
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                    + " VALUES (?, ?, ?, 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), id, status, now);
        }
    }

    private String plan(String firm, PlanServiceEnum... services) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO plans (id, deleted, created_date, created_by, name, accounting_firm_id) VALUES (?, 0, ?, 'qa', 'Plano Teste', ?)",
                id, Timestamp.valueOf(NOW), firm);
        for (PlanServiceEnum service : services) {
            jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, ?)", id, service.name());
        }
        return id;
    }

    private String company(String firm, String name, String cnpj, String uf, String plan) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')",
                document, now, cnpj);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active, uf)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, ?, 1, ?)", id, now, firm, document, name, uf);
        if (plan != null) {
            jdbc.update("INSERT INTO subscriptions (id, deleted, created_date, created_by, start_date, end_date, company_id, plan_id)"
                            + " VALUES (?, 0, ?, 'qa', ?, ?, ?, ?)", UUID.randomUUID().toString(), now,
                    java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)), java.sql.Date.valueOf(LocalDate.of(2027, 12, 31)), id, plan);
        }
        return id;
    }

    private String user(String firm, String login, String profile, String accessProfile) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CPF')",
                document, now, digits(11));
        String fullLogin = CSADMIN.equals(login) ? login : prefix + login;
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login,"
                        + " name, email, password) VALUES (?, 0, ?, 'qa', ?, ?, ?, ?, ?, ?, ?)",
                id, now, profile, firm, document, fullLogin, "Pessoa " + login + " Ficticia",
                login + "." + prefix + "@exemplo.test", passwordEncoder.encode(PASSWORD));
        if (accessProfile != null) {
            jdbc.update("INSERT INTO user_access_profiles (user_id, access_profile) VALUES (?, ?)", id, accessProfile);
        }
        return id;
    }

    private void portfolio(String user, String company) {
        jdbc.update("INSERT INTO users_companies (user_id, company_id, role, deleted, created_date, created_by)"
                + " VALUES (?, ?, 'MEMBER', 0, ?, 'qa')", user, company, Timestamp.valueOf(NOW));
    }

    private void control(String company, LocalDateTime lastAttempt, int receivedTotal, int receivedImported, int issuedTotal,
                         int issuedImported, LocalDateTime checkedAt, LocalDate sweptOn) {
        jdbc.update("DELETE FROM capture_period_counts WHERE company_id = ?", company);
        jdbc.update("DELETE FROM fiscal_xml_sync_control WHERE company_id = ?", company);
        jdbc.update("INSERT INTO fiscal_xml_sync_control (id, company_id, deleted, created_by, created_date, last_attempt_at,"
                        + " received_events_checked_on, issued_events_checked_on, service_invoices_checked_on)"
                        + " VALUES (?, ?, 0, 'qa', ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), company, Timestamp.valueOf(NOW), lastAttempt == null ? null : Timestamp.valueOf(lastAttempt),
                java.sql.Date.valueOf(sweptOn), java.sql.Date.valueOf(sweptOn), java.sql.Date.valueOf(sweptOn));
        // the SIEG counters live in capture_period_counts (FD-RN-32): November's rows, the window of the month on both sides
        counts(company, "RECEIVED", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), receivedTotal, receivedImported, checkedAt);
        counts(company, "ISSUED", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), issuedTotal, issuedImported, checkedAt);
    }

    private void counts(String company, String side, LocalDate windowStart, LocalDate windowEnd, int providerTotal, int imported,
                        LocalDateTime checkedAt) {
        jdbc.update("INSERT INTO capture_period_counts (id, company_id, channel, document_type, side, period_month, window_start,"
                        + " window_end, provider_total, provider_counted_at, imported_total, imported_counted_at, created_by,"
                        + " created_date, deleted) VALUES (?, ?, 'SIEG', 'NFE', ?, '2026-11', ?, ?, ?, ?, ?, ?, 'qa', ?, 0)",
                UUID.randomUUID().toString(), company, side, windowStart == null ? null : java.sql.Date.valueOf(windowStart),
                windowEnd == null ? null : java.sql.Date.valueOf(windowEnd), providerTotal, Timestamp.valueOf(checkedAt), imported,
                Timestamp.valueOf(checkedAt), Timestamp.valueOf(NOW));
    }

    private void certificate(String company, LocalDateTime validUntil) {
        jdbc.update("DELETE FROM company_certificates WHERE company_id = ?", company);
        jdbc.update("INSERT INTO company_certificates (id, company_id, file_path, password_encrypted, valid_until, deleted, created_by,"
                        + " created_date) VALUES (?, ?, 'certificados/ficticio.pfx', 'senha-cifrada-ficticia', ?, 0, 'qa', ?)",
                UUID.randomUUID().toString(), company, Timestamp.valueOf(validUntil), Timestamp.valueOf(NOW));
    }

    private void sefazControl(String company, LocalDateTime lastRunAt, String status, String detail) {
        jdbc.update("DELETE FROM sefaz_dist_control WHERE company_id = ?", company);
        jdbc.update("INSERT INTO sefaz_dist_control (id, company_id, ult_nsu, last_run_at, last_status, last_detail, deleted, created_by,"
                        + " created_date) VALUES (?, ?, 1, ?, ?, ?, 0, 'qa', ?)", UUID.randomUUID().toString(), company,
                Timestamp.valueOf(lastRunAt), status, detail, Timestamp.valueOf(NOW));
    }

    private void adnControl(String company, LocalDateTime lastRunAt, String status) {
        jdbc.update("DELETE FROM nfse_adn_control WHERE company_id = ?", company);
        jdbc.update("INSERT INTO nfse_adn_control (id, company_id, ult_nsu, last_run_at, last_status, last_detail, deleted, created_by,"
                        + " created_date) VALUES (?, ?, 1, ?, ?, 'detalhe antigo do ADN', 0, 'qa', ?)", UUID.randomUUID().toString(), company,
                Timestamp.valueOf(lastRunAt), status, Timestamp.valueOf(NOW));
    }

    private void removeFirm(String firm) {
        List<String> companies = jdbc.queryForList("SELECT id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
        for (String company : companies) {
            for (String table : List.of("capture_priority_requests", "capture_stage_status", "fiscal_xml_sync_control",
                    "sefaz_dist_control", "nfse_adn_control", "company_certificates", "fiscal_document_sources", "fiscal_documents", "capture_period_counts",
                    "recovery_tasks", "subscriptions", "users_companies")) {
                jdbc.update("DELETE FROM " + table + " WHERE company_id = ?", company);
            }
        }
        jdbc.update("DELETE FROM capture_priority_requests WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM recovery_tasks WHERE accounting_firm_id = ?", firm);
        List<String> plans = jdbc.queryForList("SELECT id FROM plans WHERE accounting_firm_id = ?", String.class, firm);
        for (String plan : plans) {
            jdbc.update("DELETE FROM subscriptions WHERE plan_id = ?", plan);
            jdbc.update("DELETE FROM plan_services WHERE plan_id = ?", plan);
        }
        jdbc.update("DELETE FROM plans WHERE accounting_firm_id = ?", firm);
        List<Map<String, Object>> users = jdbc.queryForList("SELECT id, document_id FROM users WHERE accounting_firm_id = ?", firm);
        for (Map<String, Object> user : users) {
            for (String table : List.of("pendency_alert_items", "pendency_alert_digests", "user_notification_settings",
                    "password_reset_tokens", "user_access_profiles", "users_companies")) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", user.get("id"));
            }
        }
        List<String> companyDocuments = jdbc.queryForList("SELECT document_id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
        jdbc.update("DELETE FROM companies WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM users WHERE accounting_firm_id = ?", firm);
        for (String document : companyDocuments) {
            jdbc.update("DELETE FROM documents WHERE id = ?", document);
        }
        for (Map<String, Object> user : users) {
            jdbc.update("DELETE FROM documents WHERE id = ?", user.get("document_id"));
        }
        jdbc.update("DELETE FROM firm_subscription_events WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firm);
    }

    static String cnpj(String root, String establishment) {
        String base = root + establishment;
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int d1 = check(base, w1);
        int d2 = check(base + d1, w2);
        return base + d1 + d2;
    }

    private static int check(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    private static String digits(int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }
}
