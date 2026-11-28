package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
import org.mockito.Mockito;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.fiscaldocument.ProviderKey;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.repository.CapturePeriodCountRepository;
import br.com.cshub.taxify.service.capture.CaptureCountsService;
import java.time.YearMonth;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SIEG capture queue (last_attempt_at and the atomic reservation) against a local MySQL: real companies, plans,
 * subscriptions and control rows, the conditional UPDATE with datetime(6) and the unique key of the control rows. The
 * provider is a recorder - no SIEG call.
 *
 * <p>Only runs with {@code DB_HOST=localhost}. Fictitious data, removed in {@code @AfterEach}.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        // the daily purge and trial notices never run on their own against the shared test database
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "fiscal-xml.storage-path=target/qa-fiscal-xml",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
class FiscalXmlSyncQueueIntegrationTest {

    private static final Random RANDOM = new Random();
    private static final ZoneId RECIFE = ZoneId.of("America/Recife");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private FiscalXmlSyncControlRepository controlRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private CaptureCountsService countsService;
    @Autowired
    private CapturePeriodCountRepository countRepository;

    private final Set<String> firms = new LinkedHashSet<>();
    private Set<String> baselineControls = Set.of();

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        // other entitled companies in the database would share the queue and skew the counts
        Assumptions.assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM subscriptions", Integer.class) == 0,
                "needs a database without other subscriptions");
        baselineControls = new HashSet<>(jdbc.queryForList("SELECT company_id FROM fiscal_xml_sync_control", String.class));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /** 300 companies, batch of 10: 30 rounds visit every one once, and a restart (new scheduler) does not start over. */
    @Test
    void theQueueVisitsEveryCompanyAndARestartDoesNotStartOver() {
        Fixture f = companies(300);
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
        // the reservation is what is in the database: every control row of the 300 has last_attempt_at
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_sync_control c JOIN companies p ON p.id = c.company_id"
                + " WHERE p.accounting_firm_id = ? AND c.last_attempt_at IS NOT NULL", Integer.class, f.firmId())).isEqualTo(300);
        // the 31st round starts the second cycle with the oldest attempts
        recorder.clear();
        queueScheduler(recorder, 10, real).syncAllCompanies();
        assertThat(recorder.visits()).hasSize(10).allSatisfy(id -> assertThat(f.companies()).contains(id));
    }

    /** A company that always fails does not block the queue: it is tried once per cycle and everybody else is visited. */
    @Test
    void aCompanyThatAlwaysFailsDoesNotBlockTheQueue() {
        Fixture f = companies(300);
        String bad = f.companies().get(137);
        RecordingProvider recorder = new RecordingProvider(bad);
        FiscalXmlSyncScheduler scheduler = queueScheduler(recorder, 10, Clock.system(RECIFE));
        for (int i = 0; i < 60; i++) {
            scheduler.syncAllCompanies();
        }
        Map<String, Long> perCompany = recorder.visits().stream().collect(Collectors.groupingBy(Function.identity(),
                Collectors.counting()));
        assertThat(perCompany).hasSize(300);
        assertThat(perCompany.values()).allSatisfy(count -> assertThat(count).isEqualTo(2L));
        assertThat(perCompany.get(bad)).isEqualTo(2L);
    }

    /** Two instances on the same database at the same time never take the same company in a round, and together cover the 300. */
    @Test
    void twoInstancesNeverTakeTheSameCompany() throws Exception {
        Fixture f = companies(300);
        RecordingProvider recorderA = new RecordingProvider(null);
        RecordingProvider recorderB = new RecordingProvider(null);
        Clock real = Clock.system(RECIFE);
        FiscalXmlSyncScheduler a = queueScheduler(recorderA, 10, real);
        FiscalXmlSyncScheduler b = queueScheduler(recorderB, 10, real);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // the first tick (single instance) seeds the control rows - seeding together is the next test
            a.syncAllCompanies();
            for (int round = 0; round < 14; round++) {
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
                List<String> both = new ArrayList<>(recorderA.visits());
                both.addAll(recorderB.visits());
                int expectedTotal = Math.min(10 + (round + 1) * 20, 300);
                assertThat(both).as("round %d: no company twice", round).hasSize(expectedTotal);
                assertThat(new HashSet<>(both)).as("round %d", round).hasSize(expectedTotal);
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> all = new ArrayList<>(recorderA.visits());
        all.addAll(recorderB.visits());
        assertThat(all).hasSize(290);
        assertThat(new HashSet<>(all)).as("no company taken twice in 14 concurrent rounds").hasSize(290).isSubsetOf(f.companies());
        assertThat(recorderA.visits()).isNotEmpty();
        assertThat(recorderB.visits()).isNotEmpty();
    }

    /**
     * Two instances ticking together on companies without a control row both INSERT it; the loser hits
     * uk_fiscal_xml_sync_control_company (MySQL 1062). Neither tick fails, every company ends with exactly one row,
     * and no company is synced by both.
     */
    @Test
    void twoInstancesSeedingTheControlRowsTogetherDoNotFail() throws Exception {
        Fixture f = companies(50);
        RecordingProvider recorderA = new RecordingProvider(null);
        RecordingProvider recorderB = new RecordingProvider(null);
        FiscalXmlSyncScheduler a = queueScheduler(recorderA, 10, Clock.system(RECIFE));
        FiscalXmlSyncScheduler b = queueScheduler(recorderB, 10, Clock.system(RECIFE));
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_sync_control c JOIN companies p ON p.id = c.company_id"
                + " WHERE p.accounting_firm_id = ?", Integer.class, f.firmId())).isEqualTo(50);
        List<String> all = new ArrayList<>(recorderA.visits());
        all.addAll(recorderB.visits());
        assertThat(all).isNotEmpty();
        assertThat(new HashSet<>(all)).as("no company synced by both").hasSize(all.size());
    }

    /** The conditional UPDATE on MySQL / datetime(6): the second reservation with the same "read" value loses; null-safe; microseconds kept. */
    @Test
    void theReservationIsAtomicWithMicroseconds() {
        Fixture f = companies(1);
        UUID companyId = UUID.fromString(f.companies().getFirst());
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(companyRepository.findById(companyId).orElseThrow());
        control.setDeleted(false);
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
        Fixture f = companies(3);
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

    // ================================================================== helpers

    private FiscalXmlSyncScheduler queueScheduler(FiscalXmlProvider provider, int batch, Clock clock) {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setEnabled(true);
        properties.setProvider("sieg");
        properties.setBatchSize(batch);
        // only the queue is under test: every firm passes the F1-RN-10 gate, and there is no blocked-months recovery
        FirmAccessService access = Mockito.mock(FirmAccessService.class);
        Mockito.when(access.routineGate(Mockito.anyString())).thenAnswer(invocation -> RoutineGate.allowAll("sieg"));
        return new FiscalXmlSyncScheduler(companyRepository, controlRepository, subscriptionRepository, Map.of("sieg", provider),
                properties, access, null, clock, null, null, countsService);
    }

    /** Records the received-side sync of each company (one visit per company per round). */
    static class RecordingProvider implements FiscalXmlProvider {
        private final List<String> visits = Collections.synchronizedList(new ArrayList<>());
        private final String failing;

        RecordingProvider(String failing) {
            this.failing = failing;
        }

        @Override
        public FiscalXmlSyncResult sync(FiscalXmlSyncRequest request) {
            if (request.side() == FiscalXmlSide.RECEIVED) {
                visits.add(request.companyId().toString());
            }
            if (request.companyId().toString().equals(failing)) {
                throw new IllegalStateException("SIEG recusou a empresa");
            }
            return new FiscalXmlSyncResult(0, 0, 0);
        }

        List<String> visits() {
            return List.copyOf(visits);
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

    /** The queue row and when each side was last checked (capture_period_counts, FD-RN-32; null = never). */
    private void seedControl(String companyId, LocalDateTime received, LocalDateTime issued) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        Company company = companyRepository.findById(UUID.fromString(companyId)).orElseThrow();
        control.setCompany(company);
        control.setDeleted(false);
        controlRepository.save(control);
        seedCount(company, CaptureSide.RECEIVED, received);
        seedCount(company, CaptureSide.ISSUED, issued);
    }

    private void seedCount(Company company, CaptureSide side, LocalDateTime checkedAt) {
        if (checkedAt == null) {
            return;
        }
        YearMonth month = YearMonth.from(checkedAt);
        CapturePeriodCount row = new CapturePeriodCount(company, ProviderKey.SIEG, FiscalDocumentType.NFE, side, month);
        row.askedWindow(month.atDay(1), checkedAt.toLocalDate());
        row.recordProviderCount(0, checkedAt);
        row.recordImported(0, checkedAt);
        row.setDeleted(false);
        countRepository.save(row);
    }

    private record Fixture(String firmId, List<String> companies) {
    }

    /** A firm with {@code companies} client companies, each on a plan with the SIEG and the XML import - all fictitious. */
    private Fixture companies(int companies) {
        String firmId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)",
                firmId, now, "Escritorio QA Fila");
        firms.add(firmId);
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
                + " VALUES (?, 0, ?, 'qa', '2026-01-01', '2099-12-31', ?, ?)", subscriptionRows);
        return new Fixture(firmId, ids);
    }

    private void cleanUp() {
        for (String firm : firms) {
            jdbc.update("DELETE c FROM capture_period_counts c JOIN companies p ON p.id = c.company_id WHERE p.accounting_firm_id = ?", firm);
            jdbc.update("DELETE c FROM fiscal_xml_sync_control c JOIN companies p ON p.id = c.company_id WHERE p.accounting_firm_id = ?", firm);
            for (String planId : jdbc.queryForList("SELECT id FROM plans WHERE accounting_firm_id = ?", String.class, firm)) {
                jdbc.update("DELETE FROM subscriptions WHERE plan_id = ?", planId);
                jdbc.update("DELETE FROM plan_services WHERE plan_id = ?", planId);
                jdbc.update("DELETE FROM plans WHERE id = ?", planId);
            }
            List<String> documents = jdbc.queryForList("SELECT document_id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
            jdbc.update("DELETE FROM companies WHERE accounting_firm_id = ?", firm);
            for (String document : documents) {
                jdbc.update("DELETE FROM documents WHERE id = ?", document);
            }
            jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firm);
        }
        // control rows the schedulers seeded for companies that were not mine (kept: those that existed before the test)
        for (String company : jdbc.queryForList("SELECT company_id FROM fiscal_xml_sync_control", String.class)) {
            if (!baselineControls.contains(company)) {
                jdbc.update("DELETE FROM fiscal_xml_sync_control WHERE company_id = ?", company);
            }
        }
        firms.clear();
    }

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
}
