package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.ServiceInvoiceCountGate;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.sieg.SiegDownloadXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegDocumentCounts;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * B1 (a captura perdia notas): o {@code skip} do SIEG era o total de notas do lado no mês vindas de QUALQUER fonte. Com
 * notas da SEFAZ (ou de upload) no banco, o SIEG pulava posições da ordem dele que não eram aquelas notas, e as notas
 * dessas posições nunca chegavam. Agora o skip conta só as notas que o próprio SIEG já entregou ({@code found_in_sieg_at}),
 * e ao chegar ao fim com menos do que o total do SIEG uma passada de reconciliação relê do início.
 *
 * <p>E a economia de chamadas (A1 NFS-e só com diferença, A2 eventos só com nota recente, A3 lado com total 0) por um
 * agendador real, contando as requisições que chegam à SIEG simulada.
 *
 * <p>Contra o MySQL local (DB_HOST=localhost); a SIEG é simulada (SiegClient mockado, uma lista fixa na "ordem da SIEG").
 * Dados fictícios; limpa no {@code @AfterEach}.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "fiscal-xml.storage-path=target/qa-fiscal-xml",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false",
        // a context of its own (the SiegClient mock): a small pool, closed after the class - the suite's cached contexts
        // already sit close to MySQL's max_connections
        "spring.datasource.hikari.maximum-pool-size=3"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SiegCallSavingsIntegrationTest {

    private static final Random RANDOM = new Random();
    private static final String SUPPLIER = "99999999000191";

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(LocalDateTime.of(2026, 12, 20, 10, 0));
        }
    }

    @MockitoBean
    private SiegClient siegClient;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Map<String, FiscalXmlProvider> providers;
    @Autowired
    private FiscalXmlIntegrationService integrationService;
    @Autowired
    private FiscalXmlSyncControlRepository controlRepository;
    @Autowired
    private FirmIntegrationService firmIntegrationService;
    @Autowired
    private AccountingFirmRepository firmRepository;

    @Autowired
    private MutableClock clock;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private FirmAccessService firmAccessService;

    private String firmId;
    private String companyId;
    private String cnpj;
    /** SIEG's own order of the month's received notes (the company as CnpjDest). */
    private final List<byte[]> siegNotes = Collections.synchronizedList(new ArrayList<>());
    /** SIEG's NFS-e with the company as CnpjDest, with their issue day (SIEG filters them by the request's UTC days). */
    private final List<ServiceInvoiceEntry> siegServiceInvoices = Collections.synchronizedList(new ArrayList<>());

    private record ServiceInvoiceEntry(LocalDate issued, byte[] xml) {
    }
    /** Notes SIEG counts on the received side but never delivers. */
    private int undeliverable;
    /** The received side's NF-e download pages (TipoXml 1, CnpjDest). */
    private final List<SiegDownloadXmlsRequest> downloads = Collections.synchronizedList(new ArrayList<>());
    /** Every request about the company, in order: "count", "nfe", "nfse". */
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        clock.set(LocalDateTime.of(2026, 12, 20, 10, 0));
        fixture();
        when(siegClient.countXmls(any(), anyString())).thenAnswer(inv -> {
            SiegDownloadXmlsRequest request = inv.getArgument(0);
            if (!mine(request)) {
                return new SiegDocumentCounts(0, 0);
            }
            calls.add("count");
            return cnpj.equals(request.cnpjDest())
                    ? new SiegDocumentCounts(siegNotes.size() + undeliverable, serviceInvoicesOf(request).size())
                    : new SiegDocumentCounts(0, 0);
        });
        when(siegClient.downloadXmls(any(), anyString())).thenAnswer(inv -> {
            SiegDownloadXmlsRequest request = inv.getArgument(0);
            if (!mine(request)) {
                return List.of();
            }
            boolean nfse = request.xmlType() != null && request.xmlType() == 3;
            calls.add(nfse ? "nfse" : "nfe");
            if (!cnpj.equals(request.cnpjDest())) {
                return List.of();
            }
            if (!nfse) {
                downloads.add(request);
            }
            List<byte[]> source = nfse ? serviceInvoicesOf(request) : siegNotes;
            synchronized (siegNotes) {
                int from = Math.min(request.skip(), source.size());
                int to = Math.min(from + request.take(), source.size());
                return new ArrayList<>(source.subList(from, to));
            }
        });
    }

    private List<byte[]> serviceInvoicesOf(SiegDownloadXmlsRequest request) {
        LocalDate from = request.issueDateFrom().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate to = request.issueDateTo().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        synchronized (siegServiceInvoices) {
            return siegServiceInvoices.stream().filter(entry -> !entry.issued().isBefore(from) && !entry.issued().isAfter(to))
                    .map(ServiceInvoiceEntry::xml).toList();
        }
    }

    private boolean mine(SiegDownloadXmlsRequest request) {
        return cnpj.equals(request.cnpjDest()) || cnpj.equals(request.cnpjEmit());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /**
     * O cenário do bug: a SEFAZ já trouxe 30 entradas do mês, que na ordem do SIEG são as 30 últimas (70 a 99). Com o skip
     * antigo (30, de qualquer fonte), as notas 0 a 29 do SIEG nunca eram pedidas. Agora todas as 100 chegam.
     */
    @Test
    void notesBroughtBySefazDoNotMakeSiegSkipOtherNotes() {
        List<String> keys = siegMonth(100);
        integrationService.importFromSefaz(UUID.fromString(companyId), new ArrayList<>(siegNotes.subList(70, 100)));
        assertThat(stored()).isEqualTo(30);

        for (int attempt = 0; attempt < 6; attempt++) {
            attempt();
        }

        assertThat(stored()).as("every note SIEG has is stored").isEqualTo(100);
        assertThat(missing(keys)).isEmpty();
        assertThat(foundInSieg()).as("the SEFAZ notes were seen in SIEG too").isEqualTo(100);
        FiscalXmlSyncControl control = controlRepository.findByCompany_Id(UUID.fromString(companyId)).orElseThrow();
        assertThat(control.getTotalSieg()).isEqualTo(100);
        assertThat(control.getTotalImported()).isEqualTo(100);
    }

    /**
     * A ordem do SIEG mudou depois de importado (notas que entraram tarde em posições anteriores): o fim da paginação chega
     * com menos do que o total, e a reconciliação relê do início - no máximo ceil(total/50) páginas - e acha as que faltam.
     */
    @Test
    void reconciliationFromTheStartFindsNotesInsertedBeforeTheSkip() {
        siegMonth(60);
        attempt();
        attempt();
        assertThat(stored()).isEqualTo(60);

        List<String> late = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String key = key();
            late.add(key);
            siegNotes.add(10 + i, nfe(key, SUPPLIER, cnpj, "2026-12-03T09:00:00-03:00"));
        }
        downloads.clear();
        attempt(true); // the next day's count: 65

        assertThat(stored()).isEqualTo(65);
        assertThat(missing(late)).isEmpty();
        // the regular page at skip 60 (5 known notes, the end) + the reconciliation from 0 (at most ceil(65/50) = 2 pages),
        // which stops on the first page: the 5 late notes were there
        assertThat(downloads).extracting(SiegDownloadXmlsRequest::skip).containsExactly(60, 0);
        FiscalXmlSyncControl control = controlRepository.findByCompany_Id(UUID.fromString(companyId)).orElseThrow();
        assertThat(control.getTotalImported()).isEqualTo(65);
        assertThat(control.getTotalSieg()).isEqualTo(65);
    }

    /**
     * SIEG counts 3 notes it never delivers: one reconciliation finds nothing more and leaves the difference (3) for the
     * month; SIEG's total stays 63. The next attempts of the day and of the next day do not re-read the month: only a
     * bigger difference would.
     */
    @Test
    void notesSiegCountsButNeverDeliversAreReadOnceNotEveryDay() {
        siegMonth(60);
        undeliverable = 3;
        attempt();
        attempt();
        // skip 0 (full page), then skip 50 (10 notes, the end, 60 of 63) + the reconciliation: pages 0 and 50
        assertThat(downloads).extracting(SiegDownloadXmlsRequest::skip).containsExactly(0, 50, 0, 50);
        assertThat(stored()).isEqualTo(60);
        Map<String, Object> row = control();
        assertThat(row.get("total_sieg")).as("SIEG's own total, not cleared").isEqualTo(63);
        assertThat(row.get("total_importado")).isEqualTo(60);
        assertThat(row.get("received_known_gap")).isEqualTo(3);
        assertThat(row.get("received_known_gap_month").toString().strip()).isEqualTo("2026-12");

        downloads.clear();
        attempt();
        assertThat(downloads).as("the same day: up to date with the known difference").isEmpty();

        clock.set(LocalDateTime.of(2026, 12, 21, 10, 0));
        attempt(true);
        assertThat(downloads).as("the next day: counted (63), nothing beyond the known difference - no page").isEmpty();
        attempt(true);
        assertThat(downloads).isEmpty();

        // one more note that SIEG does deliver: the difference does not grow, only the page after the 60 is asked
        siegNotes.add(nfe(key(), SUPPLIER, cnpj, "2026-12-20T09:00:00-03:00"));
        attempt(true);
        assertThat(downloads).extracting(SiegDownloadXmlsRequest::skip).containsExactly(60);
        assertThat(stored()).isEqualTo(61);
    }

    /** The reconciliation runs page by page in its own transactions: a failure keeps what came and records nothing. */
    @Test
    void aReconciliationThatFailsKeepsWhatCameAndIsAskedAgain() {
        siegMonth(60);
        attempt();
        attempt();
        List<String> late = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String key = key();
            late.add(key);
            siegNotes.add(10 + i, nfe(key, SUPPLIER, cnpj, "2026-12-03T09:00:00-03:00"));
        }
        for (int i = 0; i < 3; i++) {
            siegNotes.add(55 + i, nfe(key(), SUPPLIER, cnpj, "2026-12-04T09:00:00-03:00"));
        }
        // the reconciliation's second page fails
        java.util.concurrent.atomic.AtomicInteger reconcilePages = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            SiegDownloadXmlsRequest request = inv.getArgument(0);
            downloads.add(request);
            if (br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext.current()
                    == br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose.RECONCILE && reconcilePages.incrementAndGet() == 2) {
                throw new br.com.cshub.taxify.exception.SiegIntegrationException("error.sieg.integration.failed", "x", 503);
            }
            synchronized (siegNotes) {
                int from = Math.min(request.skip(), siegNotes.size());
                int to = Math.min(from + request.take(), siegNotes.size());
                return new ArrayList<>(siegNotes.subList(from, to));
            }
        }).when(siegClient).downloadXmls(any(), anyString());
        downloads.clear();
        attempt(true); // counts 66, page at 60 (6 known/new), the end with 63 of 66: reconcile 0 (ok), 50 (fails)

        assertThat(missing(late)).as("the first page's notes were kept").isEmpty();
        Map<String, Object> row = control();
        assertThat(row.get("received_known_gap")).as("an interrupted pass records nothing").isNull();
        assertThat(reconcilePages.get()).isEqualTo(2);

        // the same day, the pending still known: only the regular page - the interrupted pass is not re-read before tomorrow
        downloads.clear();
        attempt();
        attempt();
        assertThat(downloads).extracting(SiegDownloadXmlsRequest::skip).containsExactly(63, 63);
        assertThat(reconcilePages.get()).isEqualTo(2);
        assertThat(stored()).isEqualTo(63);

        // the next day the reconciliation is tried again and completes
        clock.set(LocalDateTime.of(2026, 12, 21, 10, 0));
        downloads.clear();
        attempt();
        assertThat(downloads).extracting(SiegDownloadXmlsRequest::skip).containsExactly(63, 0, 50);
        assertThat(stored()).isEqualTo(66);
        assertThat(control().get("received_known_gap")).isEqualTo(0);
    }

    /**
     * A1 + A2 + A3 through the real scheduler, database and provider (SIEG simulated): what a company costs per day.
     * Before: 2 counts + 2 event pages + 4 NFS-e pages (2 windows x 2 filters) = 8 requests a day with nothing to bring,
     * plus 2 counts more on every later attempt of the day.
     */
    @Test
    void aQuietCompanyCostsTwoCountsADayAndOnlyWhatIsNewOnTopOfThat() {
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.ON);

        clock.set(LocalDateTime.of(2026, 12, 19, 10, 0)); // first day: the row is created, the NFS-e go 3 months back
        scheduler.syncAllCompanies();
        calls.clear();

        clock.set(LocalDateTime.of(2026, 12, 20, 10, 0));
        scheduler.syncAllCompanies();
        // nothing new: the two NF-e counts (which also bring December's NFS-e count) and the NFS-e count of the
        // 15th to the 30th of November (today - 35 falls in it), as issuer and as taker
        assertThat(calls).as("the next day, nothing new: counts only").containsExactly("count", "count", "count", "count");
        Map<String, Object> row = control();
        assertThat(row.get("received_events_checked_on").toString()).as("swept without a call").isEqualTo("2026-12-20");
        assertThat(row.get("issued_events_checked_on").toString()).isEqualTo("2026-12-20");
        assertThat(row.get("service_invoices_checked_on").toString()).isEqualTo("2026-12-20");

        calls.clear();
        clock.set(LocalDateTime.of(2026, 12, 20, 11, 0));
        scheduler.syncAllCompanies();
        assertThat(calls).as("A3: both sides at 0, checked an hour ago").isEmpty();

        // the next day SIEG has one supplier note (issued yesterday) and two NFS-e taken by the company
        siegNotes.add(nfe(key(), SUPPLIER, cnpj, "2026-12-20T15:00:00-03:00"));
        siegServiceInvoices.add(new ServiceInvoiceEntry(LocalDate.of(2026, 12, 18), serviceInvoice(cnpj, "2026-12-18T10:00:00-03:00")));
        siegServiceInvoices.add(new ServiceInvoiceEntry(LocalDate.of(2026, 12, 19), serviceInvoice(cnpj, "2026-12-19T10:00:00-03:00")));
        calls.clear();
        clock.set(LocalDateTime.of(2026, 12, 21, 10, 0));
        scheduler.syncAllCompanies();

        // counts (received, issued), the received page, the received event sweep, November's NFS-e counts, and
        // December's NFS-e window (its count came with the NF-e ones) as issuer and as taker
        assertThat(calls).containsExactly("count", "nfe", "nfe", "count", "count", "count", "nfse", "nfse");
        assertThat(stored()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_invoices WHERE company_id = ? AND found_in_sieg_at IS NOT NULL",
                Integer.class, companyId)).isEqualTo(2);
        row = control();
        assertThat(row.get("total_sieg")).isEqualTo(1);
        assertThat(row.get("total_importado")).isEqualTo(1);
        assertThat(row.get("issued_events_checked_on").toString()).isEqualTo("2026-12-21");

        calls.clear();
        clock.set(LocalDateTime.of(2026, 12, 22, 10, 0));
        scheduler.syncAllCompanies();
        // the received side's count matches what SIEG delivered: no page; the note of the 20th is still in the sweep's
        // window (20 to 22): one event page; November counted again; December's NFS-e are all there: no download
        assertThat(calls).containsExactly("count", "nfe", "count", "count", "count");
        assertThat(downloads.getLast().issueDateFrom()).as("the event sweep: today - 2")
                .isEqualTo(java.time.Instant.parse("2026-12-20T00:00:00Z"));
    }

    /** The default (shadow): the NFS-e are downloaded as before - 35 days, 2 windows x 2 filters - and nothing is counted for them. */
    @Test
    void theShadowGateDownloadsTheNfseAsBefore() {
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        clock.set(LocalDateTime.of(2026, 12, 19, 10, 0));
        scheduler.syncAllCompanies();
        siegServiceInvoices.add(new ServiceInvoiceEntry(LocalDate.of(2026, 12, 19), serviceInvoice(cnpj, "2026-12-19T10:00:00-03:00")));
        calls.clear();

        clock.set(LocalDateTime.of(2026, 12, 20, 10, 0));
        scheduler.syncAllCompanies();

        assertThat(calls).containsExactly("count", "count", "nfse", "nfse", "nfse", "nfse");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_invoices WHERE company_id = ?", Integer.class, companyId)).isOne();
    }

    private FiscalXmlSyncScheduler scheduler(ServiceInvoiceCountGate gate) {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setServiceInvoiceCountGate(gate);
        properties.setEnabled(true);
        properties.setBatchSize(1000); // whatever else the local database holds; the SIEG mock only answers for this company
        return new FiscalXmlSyncScheduler(companyRepository, controlRepository, subscriptionRepository, providers, properties,
                firmAccessService, null, clock);
    }

    private Map<String, Object> control() {
        return jdbc.queryForMap("SELECT * FROM fiscal_xml_sync_control WHERE company_id = ?", companyId);
    }

    /** A national-layout NFS-e provided by a supplier and taken by {@code taker}. */
    private static byte[] serviceInvoice(String taker, String issued) {
        return ("""
                <NFSe versao="1.00" xmlns="http://www.sped.fazenda.gov.br/nfse">
                <infNFSe Id="NFS%s">
                  <xLocEmi>Recife</xLocEmi><nNFSe>%s</nNFSe><cLocIncid>2611606</cLocIncid><xLocIncid>Recife</xLocIncid>
                  <cStat>100</cStat><dhProc>%s</dhProc>
                  <emit><CNPJ>%s</CNPJ><IM>123</IM><xNome>PRESTADORA FICTICIA LTDA</xNome></emit>
                  <valores><vBC>100.00</vBC><pAliqAplic>5.00</pAliqAplic><vISSQN>5.00</vISSQN><vLiq>95.00</vLiq></valores>
                  <DPS versao="1.00"><infDPS Id="DPS1">
                    <tpAmb>1</tpAmb><dhEmi>%s</dhEmi><serie>1</serie><nDPS>1</nDPS>
                    <dCompet>2026-12-01</dCompet><tpEmit>1</tpEmit><cLocEmi>2611606</cLocEmi>
                    <prest><CNPJ>%s</CNPJ></prest>
                    <toma><CNPJ>%s</CNPJ><xNome>TOMADORA FICTICIA</xNome></toma>
                    <serv><cServ><cTribNac>010101</cTribNac><xDescServ>Servico ficticio</xDescServ></cServ></serv>
                    <valores><vServPrest><vServ>100.00</vServ></vServPrest></valores>
                  </infDPS></DPS>
                </infNFSe></NFSe>
                """).formatted("2611606" + digits(43), digits(5), issued, SUPPLIER, issued, SUPPLIER, taker)
                .getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the scheduler's call, from the control row

    /** One attempt of the received side, as FiscalXmlSyncScheduler#syncSide builds it from the control row. */
    private void attempt() {
        attempt(false);
    }

    /** @param nextDay the side was last checked on an earlier day: never up to date, and a pending is counted again */
    private void attempt(boolean nextDay) {
        LocalDate today = LocalDate.of(2026, 12, 20);
        FiscalXmlSyncControl control = controlRepository.findByCompany_Id(UUID.fromString(companyId)).orElse(null);
        boolean sameMonth = control != null && control.getIssueDateFrom() != null
                && YearMonth.from(control.getLastCheckedAt()).equals(YearMonth.from(today));
        int imported = sameMonth ? control.getTotalImported() : 0;
        int gap = control == null ? 0 : control.knownGap(false, YearMonth.from(today).toString());
        boolean knownPending = !nextDay && sameMonth && control.getTotalSieg() > 0 && imported + gap < control.getTotalSieg();
        if (!nextDay && sameMonth && control.getTotalSieg() > 0 && imported + gap >= control.getTotalSieg()) {
            return; // up to date today (FiscalXmlSyncScheduler.SideState#isUpToDate)
        }
        providers.get("sieg").sync(new FiscalXmlSyncRequest(UUID.fromString(companyId), today.withDayOfMonth(1), today, imported,
                knownPending, FiscalXmlSide.RECEIVED));
    }

    private List<String> siegMonth(int notes) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < notes; i++) {
            String key = key();
            keys.add(key);
            siegNotes.add(nfe(key, SUPPLIER, cnpj, "2026-12-%02dT09:00:00-03:00".formatted(1 + i % 19)));
        }
        return keys;
    }

    private int stored() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_documents WHERE company_id = ?", Integer.class, companyId);
    }

    private int foundInSieg() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_documents WHERE company_id = ? AND found_in_sieg_at IS NOT NULL",
                Integer.class, companyId);
    }

    private List<String> missing(List<String> keys) {
        List<String> stored = jdbc.queryForList("SELECT access_key FROM fiscal_xml_documents WHERE company_id = ?", String.class, companyId);
        return keys.stream().filter(key -> !stored.contains(key)).toList();
    }

    // ------------------------------------------------------------------ fictitious data

    private void fixture() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        firmId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', 'Escritorio QA SIEG')",
                firmId, now);
        jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, trial_ends_on, migrated, source, deleted, created_by,"
                + " created_date) VALUES (?, ?, 'ACTIVE', NULL, 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), firmId, now);
        String documentId = UUID.randomUUID().toString();
        companyId = UUID.randomUUID().toString();
        cnpj = cnpj();
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')",
                documentId, now, cnpj);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, 'Empresa Ficticia QA', 1)", companyId, now, firmId, documentId);
        String planId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO plans (id, deleted, created_date, created_by, name, accounting_firm_id) VALUES (?, 0, ?, 'qa', 'Plano QA', ?)",
                planId, now, firmId);
        jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, 'FISCAL_XML_IMPORT')", planId);
        jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, 'SIEG_INTEGRATION')", planId);
        jdbc.update("INSERT INTO subscriptions (id, deleted, created_date, created_by, start_date, end_date, company_id, plan_id)"
                + " VALUES (?, 0, ?, 'qa', '2026-01-01', '2027-12-31', ?, ?)", UUID.randomUUID().toString(), now, companyId, planId);
        AccountingFirm firm = firmRepository.findById(UUID.fromString(firmId)).orElseThrow();
        FirmIntegration integration = new FirmIntegration(firm, IntegrationType.SIEG);
        integration.setApiKey("chave-sieg-ficticia");
        firmIntegrationService.save(integration);
    }

    private void cleanUp() {
        if (firmId == null) {
            return;
        }
        jdbc.update("DELETE i FROM fiscal_xml_document_items i JOIN fiscal_xml_documents d ON d.id = i.document_id WHERE d.company_id = ?",
                companyId);
        for (int pass = 0; pass < 3; pass++) {
            for (String table : jdbc.queryForList("SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema = DATABASE()"
                    + " AND column_name = 'company_id' AND table_name NOT IN ('companies')", String.class)) {
                try {
                    jdbc.update("DELETE FROM `" + table + "` WHERE company_id = ?", companyId);
                } catch (DataAccessException ignored) {
                    // a child table first - the next pass
                }
            }
        }
        for (String planId : jdbc.queryForList("SELECT id FROM plans WHERE accounting_firm_id = ?", String.class, firmId)) {
            jdbc.update("DELETE FROM subscriptions WHERE plan_id = ?", planId);
            jdbc.update("DELETE FROM plan_services WHERE plan_id = ?", planId);
            jdbc.update("DELETE FROM plans WHERE id = ?", planId);
        }
        jdbc.update("DELETE FROM firm_integrations WHERE accounting_firm_id = ?", firmId);
        jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ?", firmId);
        String documentId = jdbc.queryForObject("SELECT document_id FROM companies WHERE id = ?", String.class, companyId);
        jdbc.update("DELETE FROM companies WHERE id = ?", companyId);
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firmId);
        firmId = null;
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
        return "2626" + digits(40);
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
