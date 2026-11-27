package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.integration.sieg.SiegBaixarXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FirmCertificateRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.FiscalXmlSyncScheduler;
import br.com.cshub.taxify.service.FirmIntegrationService;
import br.com.cshub.taxify.service.firmaccess.BlockedPeriodRecoveryService;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.fronteira.EfiscoSyncScheduler;
import br.com.cshub.taxify.service.fronteira.EfiscoSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA da decisao 20 (recuperacao dos meses bloqueados: CA1-28, CT1-22, CT1-46) e da fila da SIEG (last_attempt_at e
 * reserva atomica) contra o MySQL local. A SIEG e simulada (SiegClient mockado); o resto e real: escritorios,
 * assinaturas, tarefas de recuperacao, documentos, importacao e deduplicacao, FirmAccessService e a reserva por UPDATE
 * condicional com datetime(6).
 *
 * <p>So roda com {@code DB_HOST=localhost}. Dados ficticios; limpa no {@code @AfterEach}.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        // the daily purge and trial notices never run on their own against the shared test database
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "taxify.platform.admin-logins=qa.rec.admin",
        "taxify.platform.firm-id=" + RecoveryAndSyncQueueIntegrationTest.PLATFORM_FIRM,
        "fiscal-xml.storage-path=target/qa-fiscal-xml",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class RecoveryAndSyncQueueIntegrationTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-00000000cf03";
    private static final String PLATFORM_LOGIN = "qa.rec.admin";
    private static final String PASSWORD = "senha-de-teste-123";
    private static final String BASE = "/api/v1";
    private static final Random RANDOM = new Random();
    private static final ZoneId RECIFE = ZoneId.of("America/Recife");

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(LocalDateTime.of(2026, 12, 26, 10, 0));
        }
    }

    @MockitoBean
    private SiegClient siegClient;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private FiscalXmlSyncControlRepository controlRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private Map<String, FiscalXmlProvider> providers;
    @Autowired
    private FirmAccessService firmAccessService;
    @Autowired
    private BlockedPeriodRecoveryService recoveryService;
    @Autowired
    private FirmIntegrationService firmIntegrationService;
    @Autowired
    private FirmCertificateRepository certificateRepository;
    @Autowired
    private br.com.cshub.taxify.service.FiscalXmlIntegrationService integrationService;
    @Autowired
    private br.com.cshub.taxify.repository.AccountingFirmRepository firmRepository;

    private final Set<String> firms = new LinkedHashSet<>();
    private Set<String> baselineControls = Set.of();
    private final List<SiegBaixarXmlsRequest> downloads = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        clock.set(LocalDateTime.of(2026, 12, 26, 10, 0));
        cleanUp();
        baselineControls = new HashSet<>(jdbc.queryForList("SELECT company_id FROM fiscal_xml_sync_control", String.class));
        downloads.clear();
        firm(PLATFORM_FIRM, "CS Hub (teste QA recuperacao)", PLATFORM_LOGIN, "ACTIVE", null);
        firms.remove(PLATFORM_FIRM);
        firms.add(PLATFORM_FIRM);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ================================================================== A) recuperacao

    /** CA1-28 / CT1-22: bloqueado de 25/12 a 04/01, reativado em 04/01: a rodada seguinte busca dezembro uma vez, sem duplicar, e volta so a janela normal. */
    @Test
    void recoversDecemberOnceWithoutDuplicatingNotes() throws Exception {
        Fixture f = sieg("2026-12-24", 1);
        String company = f.companies().getFirst();
        String cnpj = cnpjOf(company);
        String k1 = key();
        String k2 = key();
        String k3 = key();
        // 10/12: a nota K1 ja foi importada (antes do bloqueio)
        clock.set(LocalDateTime.of(2026, 12, 10, 10, 0));
        when(siegClient.baixarXmls(any(), anyString())).thenAnswer(inv -> List.of(nfe(k1, "99999999000191", cnpj, "2026-12-10T10:00:00-03:00")));
        integrationService.syncPeriodPageFromSieg(UUID.fromString(company), FiscalXmlSide.RECEIVED, LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31), 0);
        assertThat(documents(company, k1)).isOne();
        Mockito.reset(siegClient);
        mockSieg(request -> december(request) ? (request.cnpjDest() != null
                ? List.of(nfe(k1, "99999999000191", cnpj, "2026-12-10T10:00:00-03:00"), nfe(k2, "99999999000191", cnpj, "2026-12-27T09:00:00-03:00"))
                : List.of(nfe(k3, cnpj, "99999999000191", "2026-12-28T09:00:00-03:00"))) : List.of());

        // 04/01: o escritorio estava bloqueado desde 25/12 (calculado) e o admin da plataforma estende o teste
        clock.set(LocalDateTime.of(2027, 1, 4, 10, 0));
        String platform = bearer(authenticate(PLATFORM_LOGIN, PASSWORD));
        JsonNode row = body(action(platform, f.firmId(), "extend-trial", Map.of("trialEndsOn", "2027-01-20", "reason", "QA")), 200);
        assertThat(row.get("status").asText()).isEqualTo("TRIAL");

        // CT1-22: dezembro (recebidas e emitidas); NFS-e dentro da janela de 35 dias e e-Fisco no mes anterior: nada extra
        List<Map<String, Object>> tasks = tasks(f.firmId());
        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(t -> t.get("source")).containsExactlyInAnyOrder("SIEG_NFE_RECEIVED", "SIEG_NFE_ISSUED");
        assertThat(tasks).allSatisfy(t -> {
            assertThat(t.get("period_start").toString()).isEqualTo("2026-12-01");
            assertThat(t.get("period_end").toString()).isEqualTo("2026-12-31");
            assertThat(t.get("status")).isEqualTo("PENDING");
        });
        JsonNode scheduled = eventAfter(f.firmId(), "RECOVERY_SCHEDULED");
        assertThat(scheduled.get("sieg_nfe").toString()).isEqualTo("[\"2026-12\"]");
        assertThat(scheduled.get("not_recovered")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT recovery_from FROM firm_subscriptions WHERE accounting_firm_id = ?", Object.class, f.firmId()).toString())
                .isEqualTo("2026-12-01");
        String owner = bearer(authenticate(f.ownerLogin(), PASSWORD));
        JsonNode view = body(mvc.perform(auth(get(BASE + "/firm-access"), owner)).andReturn(), 200);
        assertThat(view.get("recoveryPending").asBoolean()).isTrue();
        assertThat(view.get("recoveryMonths").toString()).isEqualTo("[\"2026-12\"]");

        // a rodada seguinte do SIEG
        FiscalXmlSyncScheduler scheduler = scheduler(10, LocalDate.of(2027, 1, 4), clock);
        scheduler.syncAllCompanies();
        // one step per company per round (SIEG rate limit): received and issued are two rounds
        scheduler.syncAllCompanies();

        assertThat(documents(company, k1)).as("a nota ja importada nao duplica").isOne();
        assertThat(documents(company, k2)).as("a de 27/12 entra").isOne();
        assertThat(documents(company, k3)).as("a emitida de 28/12 entra").isOne();
        assertThat(decemberDownloads()).as("dezembro e buscado uma vez por lado").hasSize(2);
        assertThat(tasks(f.firmId())).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("DONE"));
        assertThat(jdbc.queryForObject("SELECT recovery_from FROM firm_subscriptions WHERE accounting_firm_id = ?", Object.class, f.firmId())).isNull();
        JsonNode done = eventAfter(f.firmId(), "RECOVERY_DONE");
        assertThat(done.get("SIEG_NFE_RECEIVED").toString()).isEqualTo("[\"2026-12\"]");
        assertThat(done.get("SIEG_NFE_ISSUED").toString()).isEqualTo("[\"2026-12\"]");
        assertThat(body(mvc.perform(auth(get(BASE + "/firm-access"), owner)).andReturn(), 200).get("recoveryPending").asBoolean()).isFalse();

        // depois disso, so a janela normal: nada de dezembro de novo
        clock.set(LocalDateTime.of(2027, 1, 4, 10, 5));
        scheduler(10, LocalDate.of(2027, 1, 4), clock).syncAllCompanies();
        assertThat(decemberDownloads()).hasSize(2);
        assertThat(documents(company, k1) + documents(company, k2) + documents(company, k3)).isEqualTo(3);
    }

    /** Falha nao conclui, vale apos restart (instancia nova), e 10 falhas seguidas encerram o pedaco (SKIPPED). */
    @Test
    void aFailureStaysPendingSurvivesARestartAndTenFailuresGiveUp() throws Exception {
        Fixture f = sieg("2026-12-24", 1);
        String company = f.companies().getFirst();
        String cnpj = cnpjOf(company);
        String k2 = key();
        mockSieg(request -> {
            if (december(request)) {
                throw new IllegalStateException("SIEG fora do ar");
            }
            return List.of();
        });
        clock.set(LocalDateTime.of(2027, 1, 4, 10, 0));
        reactivate(f, "2027-01-20");

        scheduler(10, LocalDate.of(2027, 1, 4), clock).syncAllCompanies();
        // one step per round: the failed one is still pending with 1 attempt, the other was not touched yet
        assertThat(tasks(f.firmId())).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("PENDING"));
        assertThat(tasks(f.firmId()).stream().mapToInt(t -> ((Number) t.get("attempts")).intValue()).sum()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recovery_from FROM firm_subscriptions WHERE accounting_firm_id = ?", Object.class, f.firmId())).isNotNull();

        // "restart": a new scheduler and the SIEG back - the state is all in the database
        mockSieg(request -> december(request) && request.cnpjDest() != null
                ? List.of(nfe(k2, "99999999000191", cnpj, "2026-12-27T09:00:00-03:00")) : List.of());
        scheduler(10, LocalDate.of(2027, 1, 4), clock).syncAllCompanies();
        scheduler(10, LocalDate.of(2027, 1, 4), clock).syncAllCompanies();
        assertThat(tasks(f.firmId())).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("DONE"));
        assertThat(documents(company, k2)).isOne();

        // 10 consecutive failures of another firm: SKIPPED, and the firm still finishes (RECOVERY_DONE with skipped=2)
        Fixture g = sieg("2026-12-24", 1);
        mockSieg(request -> {
            if (december(request)) {
                throw new IllegalStateException("SIEG fora do ar");
            }
            return List.of();
        });
        reactivate(g, "2027-01-20");
        FiscalXmlSyncScheduler scheduler = scheduler(10, LocalDate.of(2027, 1, 4), clock);
        for (int i = 0; i < 20; i++) { // 10 failures per piece, one piece per round
            scheduler.syncAllCompanies();
        }
        assertThat(tasks(g.firmId())).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("SKIPPED"));
        assertThat(eventAfter(g.firmId(), "RECOVERY_DONE").get("skipped").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT recovery_from FROM firm_subscriptions WHERE accounting_firm_id = ?", Object.class, g.firmId())).isNull();
    }

    /** Novo bloqueio no meio: a recuperacao pausa (nada e buscado, nada e descartado) e continua na reativacao seguinte. */
    @Test
    void aNewBlockInTheMiddlePausesTheRecoveryAndTheNextReactivationFinishesIt() throws Exception {
        Fixture f = sieg("2026-12-24", 1);
        String company = f.companies().getFirst();
        String cnpj = cnpjOf(company);
        String k2 = key();
        mockSieg(request -> december(request) && request.cnpjDest() != null
                ? List.of(nfe(k2, "99999999000191", cnpj, "2026-12-27T09:00:00-03:00")) : List.of());
        clock.set(LocalDateTime.of(2027, 1, 4, 10, 0));
        reactivate(f, "2027-01-20");
        assertThat(tasks(f.firmId())).hasSize(2);

        // blocked again on 21/01 (trial over) before any round ran: the gate keeps everything as it was
        clock.set(LocalDateTime.of(2027, 1, 25, 10, 0));
        scheduler(10, LocalDate.of(2027, 1, 25), clock).syncAllCompanies();
        assertThat(decemberDownloads()).isEmpty();
        assertThat(tasks(f.firmId())).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("PENDING"));

        // reactivated: the periods are joined (the recovery_from of the first cycle stays), then it runs once
        reactivate(f, "2027-02-10");
        assertThat(jdbc.queryForObject("SELECT recovery_from FROM firm_subscriptions WHERE accounting_firm_id = ?", Object.class, f.firmId()).toString())
                .isEqualTo("2026-12-01");
        assertThat(tasks(f.firmId())).hasSize(2);
        scheduler(10, LocalDate.of(2027, 1, 25), clock).syncAllCompanies();
        scheduler(10, LocalDate.of(2027, 1, 25), clock).syncAllCompanies();
        assertThat(decemberDownloads()).hasSize(2);
        assertThat(documents(company, k2)).isOne();
        assertThat(tasks(f.firmId())).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("DONE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscription_events WHERE accounting_firm_id = ? AND type = 'RECOVERY_DONE'",
                Integer.class, f.firmId())).isOne();
    }

    /** CT1-46: bloqueado de 10/08 a 04/01, limite de 3 meses - nada antes de 2026-10; a trilha lista 2026-08 e 2026-09; e-Fisco e NFS-e conforme a spec. */
    @Test
    void theRecoveryLimitOfThreeMonths() throws Exception {
        Fixture f = sieg("2026-08-09", 1);
        String company = f.companies().getFirst();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO firm_certificates (id, accounting_firm_id, file_path, password_encrypted, valid_until, deleted, created_by,"
                        + " created_date) VALUES (?, ?, 'qa/ficticio.pfx', 'x', '2030-01-01 00:00:00', 0, 'qa', ?)",
                UUID.randomUUID().toString(), f.firmId(), now);
        mockSieg(request -> List.of());
        clock.set(LocalDateTime.of(2027, 1, 4, 10, 0));
        reactivate(f, "2027-01-20");

        List<Map<String, Object>> tasks = tasks(f.firmId());
        assertThat(tasks).extracting(t -> t.get("period_start").toString()).allSatisfy(start -> assertThat(start).isGreaterThanOrEqualTo("2026-10-01"));
        assertThat(months(tasks, "SIEG_NFE_RECEIVED")).containsExactly("2026-10-01", "2026-11-01", "2026-12-01");
        assertThat(months(tasks, "SIEG_NFE_ISSUED")).containsExactly("2026-10-01", "2026-11-01", "2026-12-01");
        assertThat(months(tasks, "EFISCO")).containsExactly("2026-10-01", "2026-11-01");
        assertThat(tasks.stream().filter(t -> "SIEG_NFSE".equals(t.get("source"))).map(t -> t.get("period_start") + ".." + t.get("period_end")).toList())
                .containsExactly("2026-10-01..2026-10-31", "2026-11-01..2026-11-29");
        JsonNode scheduled = eventAfter(f.firmId(), "RECOVERY_SCHEDULED");
        assertThat(scheduled.get("not_recovered").toString()).isEqualTo("[\"2026-08\",\"2026-09\"]");
        assertThat(scheduled.get("efisco").toString()).isEqualTo("[\"2026-10\",\"2026-11\"]");

        // e-Fisco: a failed run stays pending (attempts 1), the next one concludes; the months asked are 2026-10 and 2026-11 only
        EfiscoSyncService efisco = Mockito.mock(EfiscoSyncService.class);
        List<List<java.time.YearMonth>> asked = Collections.synchronizedList(new ArrayList<>());
        String[] outcome = {"ERRO"};
        when(efisco.syncFirm(any(), any())).thenAnswer(inv -> {
            List<java.time.YearMonth> months = inv.getArgument(1);
            asked.add(months);
            boolean recovery = months.contains(java.time.YearMonth.of(2026, 10));
            return new EfiscoSyncService.SyncResult(recovery ? outcome[0] : "OK", "", List.of());
        });
        EfiscoSyncScheduler efiscoScheduler = new EfiscoSyncScheduler(certificateRepository, efisco, true, firmAccessService, recoveryService);
        java.lang.reflect.Method syncAll = EfiscoSyncScheduler.class.getDeclaredMethod("syncAll");
        syncAll.setAccessible(true);
        syncAll.invoke(efiscoScheduler);
        assertThat(tasks(f.firmId()).stream().filter(t -> "EFISCO".equals(t.get("source"))))
                .allSatisfy(t -> {
                    assertThat(t.get("status")).isEqualTo("PENDING");
                    assertThat(((Number) t.get("attempts")).intValue()).isEqualTo(1);
                });
        outcome[0] = "OK";
        syncAll.invoke(efiscoScheduler);
        assertThat(tasks(f.firmId()).stream().filter(t -> "EFISCO".equals(t.get("source")))).allSatisfy(t -> assertThat(t.get("status")).isEqualTo("DONE"));
        assertThat(asked).anySatisfy(months -> assertThat(months).containsExactly(java.time.YearMonth.of(2026, 10), java.time.YearMonth.of(2026, 11)));

        // SIEG: one step per round for the company (6 NF-e pages + 2 NFS-e windows), nothing asked before 2026-10-01
        FiscalXmlSyncScheduler scheduler = scheduler(10, LocalDate.of(2027, 1, 4), clock);
        int rounds = 0;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM recovery_tasks WHERE accounting_firm_id = ? AND status = 'PENDING'", Integer.class, f.firmId()) > 0
                && rounds < 12) {
            scheduler.syncAllCompanies();
            rounds++;
        }
        assertThat(rounds).as("one step per round: 6 NF-e + 2 NFS-e").isEqualTo(8);
        assertThat(downloads).allSatisfy(r -> assertThat(r.dataEmissaoInicio().atZone(ZoneId.of("UTC")).toLocalDate()).isAfterOrEqualTo(LocalDate.of(2026, 10, 1)));
        JsonNode done = eventAfter(f.firmId(), "RECOVERY_DONE");
        assertThat(done.get("EFISCO").toString()).isEqualTo("[\"2026-10\",\"2026-11\"]");
        assertThat(done.get("SIEG_NFE_RECEIVED").toString()).isEqualTo("[\"2026-10\",\"2026-11\",\"2026-12\"]");
        assertThat(jdbc.queryForObject("SELECT recovery_from FROM firm_subscriptions WHERE accounting_firm_id = ?", Object.class, f.firmId())).isNull();
        assertThat(company).isNotNull();
    }

    // ================================================================== B) fila da SIEG

    /** 300 empresas, lote de 10: todas visitadas em 30 rodadas, exatamente uma vez; um restart no meio nao reinicia a fila. */
    @Test
    void theQueueVisitsEveryCompanyAndARestartDoesNotStartOver() {
        Fixture f = sieg("2027-06-30", 300);
        RecordingProvider recorder = new RecordingProvider(null);
        Clock real = Clock.system(RECIFE);

        FiscalXmlSyncScheduler first = queueScheduler(recorder, 10, real);
        for (int i = 0; i < 12; i++) {
            first.syncAllCompanies();
        }
        // restart: another instance, no memory of the first one
        FiscalXmlSyncScheduler second = queueScheduler(recorder, 10, real);
        for (int i = 0; i < 18; i++) {
            second.syncAllCompanies();
        }

        List<String> visits = recorder.visits();
        assertThat(visits).as("30 rounds x 10").hasSize(300);
        assertThat(new HashSet<>(visits)).as("each company once - nobody twice before everybody once").hasSize(300);
        assertThat(new HashSet<>(visits)).containsExactlyInAnyOrderElementsOf(f.companies());
        // the reservation is what is in the database: every control row of the 300 has last_attempt_at, in microseconds
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_sync_control c JOIN companies p ON p.id = c.company_id"
                + " WHERE p.accounting_firm_id = ? AND c.last_attempt_at IS NOT NULL", Integer.class, f.firmId())).isEqualTo(300);
        // the 31st round starts the second cycle with the oldest attempt
        recorder.clear();
        queueScheduler(recorder, 10, real).syncAllCompanies();
        assertThat(recorder.visits()).hasSize(10).allSatisfy(id -> assertThat(f.companies()).contains(id));
    }

    /** A company that always fails does not block the queue: it is tried once per cycle and everybody else is visited. */
    @Test
    void aCompanyThatAlwaysFailsDoesNotBlockTheQueue() {
        Fixture f = sieg("2027-06-30", 300);
        String bad = f.companies().get(137);
        RecordingProvider recorder = new RecordingProvider(bad);
        FiscalXmlSyncScheduler scheduler = queueScheduler(recorder, 10, Clock.system(RECIFE));
        for (int i = 0; i < 60; i++) {
            scheduler.syncAllCompanies();
        }
        Map<String, Long> perCompany = recorder.visits().stream().collect(java.util.stream.Collectors.groupingBy(Function.identity(),
                java.util.stream.Collectors.counting()));
        assertThat(perCompany).hasSize(300);
        assertThat(perCompany.values()).allSatisfy(count -> assertThat(count).isEqualTo(2L));
        assertThat(perCompany.get(bad)).isEqualTo(2L);
    }

    /** Two instances on the same database at the same time never take the same company in a round, and together cover the 300. */
    @Test
    void twoInstancesNeverTakeTheSameCompany() throws Exception {
        Fixture f = sieg("2027-06-30", 300);
        RecordingProvider recorderA = new RecordingProvider(null);
        RecordingProvider recorderB = new RecordingProvider(null);
        Clock real = Clock.system(RECIFE);
        FiscalXmlSyncScheduler a = queueScheduler(recorderA, 10, real);
        FiscalXmlSyncScheduler b = queueScheduler(recorderB, 10, real);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // the first tick (single instance) seeds the control rows - see BUG-QA-05 for two instances seeding together
            recorderA.clear();
            a.syncAllCompanies();
            for (int round = 0; round < 14; round++) {
                // (the visits accumulate: a company visited twice would show as a duplicate)
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> fa = pool.submit(() -> {
                    await(barrier);
                    a.syncAllCompanies();
                });
                Future<?> fb = pool.submit(() -> {
                    await(barrier);
                    b.syncAllCompanies();
                });
                fa.get();
                fb.get();
                List<String> both = new ArrayList<>(recorderA.allVisitsEver());
                both.addAll(recorderB.allVisitsEver());
                int expectedTotal = Math.min(10 + (round + 1) * 20, 300);
                assertThat(both).as("round %d: no company twice", round).hasSize(expectedTotal);
                assertThat(new HashSet<>(both)).as("round %d", round).hasSize(expectedTotal);
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> all = new ArrayList<>(recorderA.allVisitsEver());
        all.addAll(recorderB.allVisitsEver());
        assertThat(all).hasSize(290);
        assertThat(new HashSet<>(all)).as("no company taken twice in 14 concurrent rounds").hasSize(290).isSubsetOf(f.companies());
        assertThat(recorderA.allVisitsEver()).isNotEmpty();
        assertThat(recorderB.allVisitsEver()).isNotEmpty();
    }

    /**
     * BUG-QA-05: two instances ticking together on a table without the control row of a company both try to INSERT it;
     * the loser fails with the unique key violation (uk_fiscal_xml_sync_control_company) and its whole tick is lost
     * (ensureControlRowsExist is not race-safe). Self-heals on the next tick. Enable when fixed.
     */
    @Test
    void twoInstancesSeedingTheControlRowsTogetherDoNotFail() throws Exception {
        sieg("2027-06-30", 50);
        FiscalXmlSyncScheduler a = queueScheduler(new RecordingProvider(null), 10, Clock.system(RECIFE));
        FiscalXmlSyncScheduler b = queueScheduler(new RecordingProvider(null), 10, Clock.system(RECIFE));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<?> fa = pool.submit(() -> {
                await(barrier);
                a.syncAllCompanies();
            });
            Future<?> fb = pool.submit(() -> {
                await(barrier);
                b.syncAllCompanies();
            });
            fa.get();
            fb.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /** The conditional UPDATE on MySQL 5.7 / datetime(6): the second reservation with the same "read" value loses; null-safe; microseconds kept. */
    @Test
    void theReservationIsAtomicWithMicroseconds() {
        Fixture f = sieg("2027-06-30", 1);
        UUID companyId = UUID.fromString(f.companies().getFirst());
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(companyRepository.findById(companyId).orElseThrow());
        control.setDeleted(false);
        control.setTotalSieg(0);
        control.setTotalImportado(0);
        control.setUltimaVerificacaoEm(LocalDateTime.now());
        control = controlRepository.save(control);

        LocalDateTime t1 = LocalDateTime.now(Clock.system(RECIFE)).truncatedTo(ChronoUnit.MICROS).withNano(123_456_000);
        assertThat(controlRepository.reserve(control.getId(), null, t1)).as("null read, null row").isOne();
        assertThat(controlRepository.reserve(control.getId(), null, t1.plusSeconds(1))).as("same stale read loses").isZero();
        LocalDateTime stored = jdbc.queryForObject("SELECT last_attempt_at FROM fiscal_xml_sync_control WHERE id = ?", LocalDateTime.class,
                control.getId().toString());
        assertThat(stored).as("microseconds kept (datetime(6))").isEqualTo(t1);
        LocalDateTime t2 = t1.plusNanos(1_000);
        assertThat(controlRepository.reserve(control.getId(), t1, t2)).as("exact read value wins").isOne();
        assertThat(controlRepository.reserve(control.getId(), t1, t2.plusSeconds(1))).as("t1 is no longer the value").isZero();
        // a read that differs only in the last microsecond does not match
        assertThat(controlRepository.reserve(control.getId(), t2.plusNanos(1_000), t2.plusSeconds(5))).isZero();
        assertThat(jdbc.queryForObject("SELECT last_attempt_at FROM fiscal_xml_sync_control WHERE id = ?", LocalDateTime.class,
                control.getId().toString())).isEqualTo(t2);
    }

    /** Rows that already existed (last_attempt_at null) enter by the real verification: issued side never checked first, then the oldest. */
    @Test
    void existingRowsEnterByTheRealVerification() {
        Fixture f = sieg("2027-06-30", 3);
        List<String> ids = f.companies();
        LocalDateTime base = LocalDateTime.of(2026, 11, 1, 8, 0);
        // [0]: both checked recently; [1]: both checked long ago; [2]: received checked, issued never
        seedControl(ids.get(0), base.plusDays(20), base.plusDays(20));
        seedControl(ids.get(1), base, base);
        seedControl(ids.get(2), base.plusDays(25), null);
        RecordingProvider recorder = new RecordingProvider(null);
        FiscalXmlSyncScheduler scheduler = queueScheduler(recorder, 1, Clock.system(RECIFE));
        for (int i = 0; i < 3; i++) {
            scheduler.syncAllCompanies();
        }
        assertThat(recorder.visits()).containsExactly(ids.get(2), ids.get(1), ids.get(0));
    }

    // ================================================================== helpers: scheduler

    private FiscalXmlSyncScheduler scheduler(int batch, LocalDate reference, Clock clock) {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setEnabled(true);
        properties.setProvider("sieg");
        properties.setBatchSize(batch);
        properties.setReferenceDate(reference);
        return new FiscalXmlSyncScheduler(companyRepository, controlRepository, subscriptionRepository, providers, properties,
                firmAccessService, recoveryService, clock);
    }

    private FiscalXmlSyncScheduler queueScheduler(FiscalXmlProvider provider, int batch, Clock clock) {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setEnabled(true);
        properties.setProvider("sieg");
        properties.setBatchSize(batch);
        return new FiscalXmlSyncScheduler(companyRepository, controlRepository, subscriptionRepository, Map.of("sieg", provider),
                properties, firmAccessService, recoveryService, clock);
    }

    /** Records the received-side sync of each company (one visit per company per round). */
    static class RecordingProvider implements FiscalXmlProvider {
        private final List<String> visits = Collections.synchronizedList(new ArrayList<>());
        private final List<String> everVisited = Collections.synchronizedList(new ArrayList<>());
        private final String failing;

        RecordingProvider(String failing) {
            this.failing = failing;
        }

        @Override
        public FiscalXmlSyncResult sync(FiscalXmlSyncRequest request) {
            if (request.side() == FiscalXmlSide.RECEIVED) {
                visits.add(request.companyId().toString());
                everVisited.add(request.companyId().toString());
            }
            if (request.companyId().toString().equals(failing)) {
                throw new IllegalStateException("SIEG recusou a empresa");
            }
            return new FiscalXmlSyncResult(0, 0, 0);
        }

        List<String> visits() {
            return List.copyOf(visits);
        }

        List<String> allVisitsEver() {
            return List.copyOf(everVisited);
        }

        void clear() {
            visits.clear();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void seedControl(String companyId, LocalDateTime received, LocalDateTime issued) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(companyRepository.findById(UUID.fromString(companyId)).orElseThrow());
        control.setDeleted(false);
        control.setTotalSieg(0);
        control.setTotalImportado(0);
        control.setUltimaVerificacaoEm(received);
        control.setIssuedCheckedAt(issued);
        controlRepository.save(control);
    }

    // ================================================================== helpers: SIEG mock and data

    private interface SiegAnswer {
        List<byte[]> answer(SiegBaixarXmlsRequest request);
    }

    private void mockSieg(SiegAnswer answer) {
        Mockito.reset(siegClient);
        downloads.clear();
        when(siegClient.baixarXmls(any(), anyString())).thenAnswer(inv -> {
            SiegBaixarXmlsRequest request = inv.getArgument(0);
            downloads.add(request);
            return answer.answer(request);
        });
    }

    private static boolean december(SiegBaixarXmlsRequest request) {
        return request.tipoXml() != null && request.tipoXml() == 1 && Boolean.TRUE.equals(request.baixarEventos())
                && request.dataEmissaoInicio().atZone(ZoneId.of("UTC")).toLocalDate().equals(LocalDate.of(2026, 12, 1));
    }

    private List<SiegBaixarXmlsRequest> decemberDownloads() {
        synchronized (downloads) {
            return downloads.stream().filter(RecoveryAndSyncQueueIntegrationTest::december).toList();
        }
    }

    private static byte[] nfe(String key, String emit, String dest, String issued) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe>
                    <infNFe Id="NFe%s" versao="4.00">
                      <ide><dhEmi>%s</dhEmi></ide>
                      <emit><CNPJ>%s</CNPJ></emit>
                      <dest><CNPJ>%s</CNPJ></dest>
                    </infNFe>
                  </NFe>
                </nfeProc>
                """).formatted(key, issued, emit, dest).getBytes(StandardCharsets.UTF_8);
    }

    private static String key() {
        return "3526" + digits(40);
    }

    private int documents(String companyId, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_documents WHERE company_id = ? AND access_key = ?", Integer.class, companyId, key);
    }

    private String cnpjOf(String companyId) {
        return jdbc.queryForObject("SELECT d.number FROM companies c JOIN documents d ON d.id = c.document_id WHERE c.id = ?", String.class, companyId);
    }

    private List<Map<String, Object>> tasks(String firmId) {
        return jdbc.queryForList("SELECT source, period_start, period_end, status, attempts, page_cursor FROM recovery_tasks"
                + " WHERE accounting_firm_id = ? ORDER BY source, period_start", firmId);
    }

    private static List<String> months(List<Map<String, Object>> tasks, String source) {
        return tasks.stream().filter(t -> source.equals(t.get("source"))).map(t -> t.get("period_start").toString()).sorted().toList();
    }

    private JsonNode eventAfter(String firmId, String type) throws Exception {
        List<String> rows = jdbc.queryForList("SELECT after_json FROM firm_subscription_events WHERE accounting_firm_id = ? AND type = ?"
                + " ORDER BY created_date DESC", String.class, firmId, type);
        assertThat(rows).as("event " + type).isNotEmpty();
        return json.readTree(rows.getFirst());
    }

    // ================================================================== helpers: HTTP

    private record Fixture(String firmId, String ownerLogin, List<String> companies) {
    }

    /** Reactivates through the platform console (extend-trial) at the current test clock. */
    private void reactivate(Fixture f, String trialEndsOn) throws Exception {
        String platform = bearer(authenticate(PLATFORM_LOGIN, PASSWORD));
        body(action(platform, f.firmId(), "extend-trial", Map.of("trialEndsOn", trialEndsOn, "reason", "QA")), 200);
    }

    private MvcResult action(String platformBearer, String firmId, String action, Map<String, Object> request) throws Exception {
        return mvc.perform(auth(post(BASE + "/platform/firms/" + firmId + "/access/" + action), platformBearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andReturn();
    }

    private MvcResult authenticate(String login, String password) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return mvc.perform(post(BASE + "/auth").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("auth", auth)))).andReturn();
    }

    private String bearer(MvcResult authenticated) throws Exception {
        return body(authenticated, 200).get("token").asText();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private JsonNode body(MvcResult result, int expectedStatus) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(expectedStatus);
        return json.readTree(content.isEmpty() ? "{}" : content);
    }

    // ================================================================== helpers: database

    /**
     * A firm in TRIAL until {@code trialEndsOn} with ADMIN, {@code companies} client companies each on a plan with the SIEG
     * and the XML import, and the SIEG key - everything fictitious.
     */
    private Fixture sieg(String trialEndsOn, int companies) {
        String firmId = UUID.randomUUID().toString();
        String login = "qa.rec." + digits(8);
        firm(firmId, "Escritorio QA Recuperacao", login, "TRIAL", trialEndsOn);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String planId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO plans (id, deleted, created_date, created_by, name, accounting_firm_id) VALUES (?, 0, ?, 'qa', 'Plano QA', ?)",
                planId, now, firmId);
        jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, 'FISCAL_XML_IMPORT')", planId);
        jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, 'SIEG_INTEGRATION')", planId);
        List<String> ids = new ArrayList<>();
        List<Object[]> documentRows = new ArrayList<>();
        List<Object[]> companyRows = new ArrayList<>();
        List<Object[]> subscriptionRows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < companies; i++) {
            String documentId = UUID.randomUUID().toString();
            String companyId = UUID.randomUUID().toString();
            String cnpj;
            do {
                cnpj = cnpj();
            } while (!used.add(cnpj));
            documentRows.add(new Object[]{documentId, now, cnpj});
            companyRows.add(new Object[]{companyId, now, firmId, documentId});
            subscriptionRows.add(new Object[]{UUID.randomUUID().toString(), now, companyId, planId});
            ids.add(companyId);
        }
        jdbc.batchUpdate("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')", documentRows);
        jdbc.batchUpdate("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, 'Empresa Ficticia QA', 1)", companyRows);
        jdbc.batchUpdate("INSERT INTO subscriptions (id, deleted, created_date, created_by, start_date, end_date, company_id, plan_id)"
                + " VALUES (?, 0, ?, 'qa', '2026-01-01', '2027-12-31', ?, ?)", subscriptionRows);
        AccountingFirm accountingFirm = firmRepository.findById(UUID.fromString(firmId)).orElseThrow();
        FirmIntegration integration = new FirmIntegration(accountingFirm, IntegrationType.SIEG);
        integration.setApiKey("chave-sieg-ficticia");
        firmIntegrationService.save(integration);
        return new Fixture(firmId, login, ids);
    }

    private void firm(String firmId, String name, String adminLogin, String status, String trialEndsOn) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)", firmId, now, name);
        firms.add(firmId);
        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CPF')",
                documentId, now, cpf());
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name,"
                        + " email, password) VALUES (?, 0, ?, 'qa', 'ADMIN', ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), now, firmId, documentId, adminLogin, "Admin " + name, adminLogin + "@example.com",
                passwordEncoder.encode(PASSWORD));
        if (!PLATFORM_FIRM.equals(firmId)) {
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, trial_ends_on, migrated, source, deleted, created_by,"
                    + " created_date) VALUES (?, ?, ?, ?, 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), firmId, status,
                    trialEndsOn, now);
        }
    }

    private void cleanUp() {
        List<String> all = new ArrayList<>(firms);
        all.add(PLATFORM_FIRM);
        all.addAll(jdbc.queryForList("SELECT accounting_firm_id FROM users WHERE login = ?", String.class, PLATFORM_LOGIN));
        List<String> firmList = all.stream().distinct().toList();
        for (String firm : firmList) {
            List<String> companies = jdbc.queryForList("SELECT id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
            jdbc.update("DELETE FROM recovery_tasks WHERE accounting_firm_id = ?", firm);
            for (String company : companies) {
                jdbc.update("DELETE i FROM fiscal_xml_document_items i JOIN fiscal_xml_documents d ON d.id = i.document_id WHERE d.company_id = ?", company);
                for (int pass = 0; pass < 3; pass++) {
                    for (String table : jdbc.queryForList("SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema = DATABASE()"
                            + " AND column_name = 'company_id' AND table_name NOT IN ('companies')", String.class)) {
                        try {
                            jdbc.update("DELETE FROM `" + table + "` WHERE company_id = ?", company);
                        } catch (DataAccessException ignored) {
                            // a child table first - the next pass
                        }
                    }
                }
            }
            jdbc.update("DELETE FROM firm_integrations WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM firm_certificates WHERE accounting_firm_id = ?", firm);
            for (String planId : jdbc.queryForList("SELECT id FROM plans WHERE accounting_firm_id = ?", String.class, firm)) {
                jdbc.update("DELETE FROM subscriptions WHERE plan_id = ?", planId);
                jdbc.update("DELETE FROM plan_services WHERE plan_id = ?", planId);
                jdbc.update("DELETE FROM plans WHERE id = ?", planId);
            }
            List<Map<String, Object>> users = jdbc.queryForList("SELECT id, document_id FROM users WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> user : users) {
                jdbc.update("DELETE FROM user_access_profiles WHERE user_id = ?", user.get("id"));
                jdbc.update("DELETE FROM users_companies WHERE user_id = ?", user.get("id"));
            }
            jdbc.update("DELETE FROM firm_subscription_events WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ?", firm);
            List<Map<String, Object>> companyDocs = jdbc.queryForList("SELECT document_id FROM companies WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM companies WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> company : companyDocs) {
                jdbc.update("DELETE FROM documents WHERE id = ?", company.get("document_id"));
            }
            jdbc.update("DELETE FROM users WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> user : users) {
                jdbc.update("DELETE FROM documents WHERE id = ?", user.get("document_id"));
            }
            jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firm);
        }
        // sync control rows the schedulers seeded for companies that were not mine (kept: those that existed before the test)
        if (!baselineControls.isEmpty() || !firmList.isEmpty()) {
            for (String company : jdbc.queryForList("SELECT company_id FROM fiscal_xml_sync_control", String.class)) {
                if (!baselineControls.contains(company)) {
                    jdbc.update("DELETE FROM fiscal_xml_sync_control WHERE company_id = ?", company);
                }
            }
        }
        firms.clear();
    }

    // ================================================================== helpers: fictitious data

    private static String digits(int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append(RANDOM.nextInt(10));
        }
        return digits.toString();
    }

    private static String cnpj() {
        String base = "9" + digits(7) + "0001";
        int d1 = cnpjDigit(base, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = cnpjDigit(base + d1, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    private static int cnpjDigit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    private static String cpf() {
        String base;
        do {
            base = digits(9);
        } while (base.chars().distinct().count() == 1);
        int d1 = cpfDigit(base, 10);
        int d2 = cpfDigit(base + d1, 11);
        return base + d1 + d2;
    }

    private static int cpfDigit(String digits, int firstWeight) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * (firstWeight - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }
}
