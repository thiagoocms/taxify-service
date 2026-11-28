package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.ServiceInvoiceCountGate;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventParserTest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext;
import br.com.cshub.taxify.integration.sieg.SiegDownloadXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegDocumentCounts;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.service.capture.CaptureCountsService;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.capture.CapturePriorityService;
import br.com.cshub.taxify.service.capture.CaptureStageRecorder;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import ch.qos.logback.classic.Level;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * QA (agente qa-taxify): a captura do SIEG com as economias, pelo agendador REAL, o provedor REAL e o banco, contra um
 * SIEG simulado fiel (ordem fixa, janela em UTC, contagem que inclui nota que nunca entrega, filtro por CnpjEmit/CnpjDest,
 * eventos junto da pagina). Foco: nenhuma nota deixa de ser capturada por causa das economias.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "fiscal-xml.storage-path=target/qa-fiscal-xml",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false", "sieg.request-log.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=6"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SiegCallSavingsQaTest {

    private static final Random RANDOM = new Random();
    private static final String SUPPLIER = "99999999000191";
    private static final String OTHER = "88888888000191";

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
    @Autowired
    private CapturePriorityService priorityService;
    @Autowired
    private CaptureStageRecorder stageRecorder;
    @Autowired
    private CaptureCountsService countsService;

    private String firmId;
    private String companyId;
    private String cnpj;
    private final Sieg sieg = new Sieg();
    private ListAppender<ILoggingEvent> logs;

    // ------------------------------------------------------------------------------------- the simulated SIEG

    record Nfe(String key, String emit, String dest, Instant issued, boolean deliverable, byte[] xml) {
    }

    record Nfse(String id, String prest, String toma, Instant issued, byte[] xml) {
    }

    /** One request seen by the simulated SIEG. */
    record Call(String day, String kind, String filter, int skip, String purpose, String window) {
    }

    static class Sieg {
        final List<Nfe> nfes = new CopyOnWriteArrayList<>();
        final List<Nfse> nfses = new CopyOnWriteArrayList<>();
        final Map<String, List<byte[]>> events = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        volatile Predicate<SiegDownloadXmlsRequest> failDownload = r -> false;
        /** when set, NFS-e count answers this instead of the truth (a stale/lying count) */
        volatile Integer nfseCountLie;
        volatile String day = "";

        List<Nfe> matchNfe(SiegDownloadXmlsRequest r) {
            return nfes.stream().filter(n -> !n.issued().isBefore(r.issueDateFrom()) && !n.issued().isAfter(r.issueDateTo()))
                    .filter(n -> r.cnpjDest() == null || r.cnpjDest().equals(n.dest()))
                    .filter(n -> r.cnpjEmit() == null || r.cnpjEmit().equals(n.emit())).toList();
        }

        List<Nfse> matchNfse(SiegDownloadXmlsRequest r) {
            return nfses.stream().filter(n -> !n.issued().isBefore(r.issueDateFrom()) && !n.issued().isAfter(r.issueDateTo()))
                    .filter(n -> r.cnpjDest() == null || r.cnpjDest().equals(n.toma()))
                    .filter(n -> r.cnpjEmit() == null || r.cnpjEmit().equals(n.prest())).toList();
        }

        String filterOf(SiegDownloadXmlsRequest r) {
            return r.cnpjDest() != null ? "R" : r.cnpjEmit() != null ? "I" : "-";
        }

        long count(String kind) {
            return calls.stream().filter(c -> c.kind().equals(kind)).count();
        }

        long count(String day, String kind) {
            return calls.stream().filter(c -> c.day().equals(day) && c.kind().equals(kind)).count();
        }
    }

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        System.out.println("QA-JDBC " + connection);
        fixture();
        when(siegClient.countXmls(any(), anyString())).thenAnswer(inv -> {
            SiegDownloadXmlsRequest r = inv.getArgument(0);
            sieg.calls.add(new Call(sieg.day, "count", sieg.filterOf(r), r.skip() == null ? 0 : r.skip(), purpose(), window(r)));
            if (sieg.failDownload.test(r) && r.xmlType() == 99) {
                throw new RuntimeException("x");
            }
            int nfse = sieg.nfseCountLie != null ? (r.cnpjDest() != null ? sieg.nfseCountLie : 0) : sieg.matchNfse(r).size();
            return new SiegDocumentCounts(sieg.matchNfe(r).size(), nfse);
        });
        when(siegClient.downloadXmls(any(), anyString())).thenAnswer(inv -> {
            SiegDownloadXmlsRequest r = inv.getArgument(0);
            boolean nfse = r.xmlType() != null && r.xmlType() == 3;
            sieg.calls.add(new Call(sieg.day, nfse ? "nfse" : "nfe", sieg.filterOf(r), r.skip(), purpose(), window(r)));
            if (sieg.failDownload.test(r)) {
                throw new br.com.cshub.taxify.exception.SiegIntegrationException("error.sieg.integration.failed", "x", 503);
            }
            List<byte[]> page = new ArrayList<>();
            if (nfse) {
                List<Nfse> all = sieg.matchNfse(r);
                int from = Math.min(r.skip(), all.size());
                all.subList(from, Math.min(from + r.take(), all.size())).forEach(n -> page.add(n.xml()));
            } else {
                List<Nfe> all = sieg.matchNfe(r).stream().filter(Nfe::deliverable).toList();
                int from = Math.min(r.skip(), all.size());
                for (Nfe n : all.subList(from, Math.min(from + r.take(), all.size()))) {
                    page.add(n.xml());
                    if (Boolean.TRUE.equals(r.includeEvents())) {
                        page.addAll(sieg.events.getOrDefault(n.key(), List.of()));
                    }
                }
            }
            return page;
        });
        Logger root = (Logger) LoggerFactory.getLogger("br.com.cshub.taxify");
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.INFO);
    }

    private static String purpose() {
        return String.valueOf(ProviderCallContext.current());
    }

    private static String window(SiegDownloadXmlsRequest r) {
        return r.issueDateFrom().toString().substring(0, 10) + ".." + r.issueDateTo().toString().substring(0, 10);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("br.com.cshub.taxify")).detachAppender(logs);
        // LGPD: the savings' logs carry no CNPJ / company name / SIEG key
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage();
            assertThat(text).as("log must not carry the company's CNPJ").doesNotContain(cnpj);
            assertThat(text).doesNotContain("chave-sieg-ficticia").doesNotContain("Empresa Ficticia QA");
        }
        cleanUp();
    }

    private List<String> logsContaining(String text) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains(text)).toList();
    }

    // ------------------------------------------------------------------------------------- scheduler helpers

    private FiscalXmlSyncScheduler scheduler(ServiceInvoiceCountGate gate) {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setServiceInvoiceCountGate(gate);
        properties.setEnabled(true);
        properties.setBatchSize(1000);
        return new FiscalXmlSyncScheduler(companyRepository, controlRepository, subscriptionRepository, providers, properties,
                firmAccessService, null, clock, stageRecorder, priorityService, countsService);
    }

    /** One tick of the real scheduler at the Recife instant. */
    private void tick(FiscalXmlSyncScheduler scheduler, String recife) {
        LocalDateTime when = LocalDateTime.parse(recife);
        clock.set(when);
        sieg.day = when.toLocalDate().toString();
        scheduler.syncAllCompanies();
    }

    /** Ticks every 2 h from 06:00 to 22:00 on each day, inclusive. */
    private void days(FiscalXmlSyncScheduler scheduler, String fromDay, String toDay) {
        for (LocalDate day = LocalDate.parse(fromDay); !day.isAfter(LocalDate.parse(toDay)); day = day.plusDays(1)) {
            for (int hour = 6; hour <= 22; hour += 4) {
                tick(scheduler, day + "T%02d:00:00".formatted(hour));
            }
        }
    }

    // ------------------------------------------------------------------------------------- builders

    private Nfe addNfe(String emit, String dest, String issuedLocal, boolean deliverable) {
        return addNfeAt(-1, emit, dest, issuedLocal, deliverable);
    }

    private Nfe addNfeAt(int index, String emit, String dest, String issuedLocal, boolean deliverable) {
        String key = "2626" + digits(40);
        Instant issued = OffsetDateTime.parse(issuedLocal).toInstant();
        Nfe nfe = new Nfe(key, emit, dest, issued, deliverable, nfeXml(key, emit, dest, issuedLocal));
        if (index < 0) {
            sieg.nfes.add(nfe);
        } else {
            sieg.nfes.add(index, nfe);
        }
        return nfe;
    }

    private List<Nfe> received(int count, String dayFrom) {
        List<Nfe> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(addNfe(SUPPLIER, cnpj, dayFrom + "T09:%02d:00-03:00".formatted(i % 60), true));
        }
        return list;
    }

    private Nfse addNfse(String prest, String toma, String issuedLocal) {
        Instant issued = OffsetDateTime.parse(issuedLocal).toInstant();
        Nfse n = new Nfse(digits(8), prest, toma, issued, nfseXml(prest, toma, issuedLocal));
        sieg.nfses.add(n);
        return n;
    }

    private static byte[] nfeXml(String key, String emit, String dest, String issued) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe>
                    <infNFe Id="NFe%s" versao="4.00">
                      <ide><mod>55</mod><dhEmi>%s</dhEmi></ide>
                      <emit><CNPJ>%s</CNPJ></emit>
                      <dest><CNPJ>%s</CNPJ></dest>
                    </infNFe>
                  </NFe>
                </nfeProc>
                """).formatted(key, issued, emit, dest).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nfseXml(String prest, String toma, String issued) {
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
                """).formatted("2611606" + digits(43), digits(5), issued, prest, issued, prest, toma)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String cancelEvent(String key) {
        return FiscalEventParserTest.procEvento("110111", "<descEvento>Cancelamento</descEvento><xJust>Erro</xJust>", "135")
                .replace(FiscalEventParserTest.class.getName(), "").replaceAll("<chNFe>\\d+</chNFe>", "<chNFe>" + key + "</chNFe>");
    }

    // ------------------------------------------------------------------------------------- queries

    /**
     * The received side's counters of the month last checked, read from capture_period_counts (FD-RN-32) under the names
     * of the old fiscal_xml_sync_control columns these scenarios were written with.
     */
    private Map<String, Object> control() {
        return jdbc.queryForMap("SELECT period_month, provider_total AS total_sieg, imported_total AS total_importado,"
                + " known_gap AS received_known_gap, stored_any_source_total AS received_stored_any_source FROM capture_period_counts"
                + " WHERE company_id = ? AND channel = 'SIEG' AND document_type = 'NFE' AND side = 'RECEIVED' AND window_start IS NOT NULL"
                + " ORDER BY period_month DESC LIMIT 1", companyId);
    }

    private int stored() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents WHERE company_id = ? AND document_type IN ('NFE', 'NFCE')",
                Integer.class, companyId);
    }

    private int foundInSieg() {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT s.document_id) FROM fiscal_document_sources s JOIN fiscal_documents d"
                + " ON d.id = s.document_id WHERE s.company_id = ? AND s.source = 'SIEG' AND d.document_type IN ('NFE', 'NFCE')",
                Integer.class, companyId);
    }

    private int nfseStored() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents WHERE company_id = ? AND document_type = 'NFSE'",
                Integer.class, companyId);
    }

    private List<String> missing(List<Nfe> expected) {
        List<String> stored = jdbc.queryForList("SELECT access_key FROM fiscal_documents WHERE company_id = ?", String.class, companyId);
        return expected.stream().map(Nfe::key).filter(k -> !stored.contains(k)).toList();
    }

    /** NF-e pages of the regular paging and of reconciliations (the event sweep is counted apart). */
    private long nfeDownloads(String day) {
        return sieg.calls.stream().filter(c -> c.day().equals(day) && c.kind().equals("nfe")
                && (c.purpose().equals("PAGE") || c.purpose().equals("RECONCILE"))).count();
    }

    private String callsSummary() {
        return sieg.calls.stream().collect(Collectors.groupingBy(c -> c.day() + " " + c.kind() + "/" + c.purpose(), java.util.TreeMap::new,
                Collectors.counting())).toString();
    }

    // ============================================================================================= (1) B1

    /** 100 no SIEG, 30 ja trazidas pela SEFAZ - em qualquer posicao da ordem do SIEG: 100/100. */
    @Test
    void s01_b1_hundredNotes_thirtyFromSefaz_anywhere() {
        for (String where : List.of("tail", "head", "middle", "spread")) {
            sieg.nfes.clear();
            cleanCompanyData();
            List<Nfe> notes = received(100, "2026-12-03");
            List<Nfe> sefaz = switch (where) {
                case "tail" -> notes.subList(70, 100);
                case "head" -> notes.subList(0, 30);
                case "middle" -> notes.subList(35, 65);
                default -> java.util.stream.IntStream.range(0, 100).filter(i -> i % 3 == 0).limit(30).mapToObj(notes::get).toList();
            };
            integrationService.importFromSefaz(UUID.fromString(companyId), new ArrayList<>(sefaz.stream().map(Nfe::xml).toList()));
            assertThat(stored()).isEqualTo(30);
            FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
            sieg.calls.clear();
            days(scheduler, "2026-12-10", "2026-12-12");
            System.out.println("QA-S01 " + where + " stored=" + stored() + " found=" + foundInSieg() + " missing=" + missing(notes).size());
            assertThat(missing(notes)).as(where).isEmpty();
            assertThat(stored()).as(where).isEqualTo(100);
            assertThat(foundInSieg()).as(where).isEqualTo(100);
            System.out.println("QA-S01 " + where + " nfe pages=" + sieg.count("nfe") + " counts=" + sieg.count("count"));
        }
    }

    /** SEFAZ also holds notes SIEG does not have: they never count as SIEG's. */
    @Test
    void s01b_sefazExtrasDoNotHideSiegNotes() {
        List<Nfe> notes = received(60, "2026-12-03");
        List<byte[]> extras = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String key = "2626" + digits(40);
            extras.add(nfeXml(key, OTHER, cnpj, "2026-12-0%dT10:00:00-03:00".formatted(1 + i % 9)));
        }
        integrationService.importFromSefaz(UUID.fromString(companyId), extras);
        days(scheduler(ServiceInvoiceCountGate.SHADOW), "2026-12-10", "2026-12-12");
        assertThat(missing(notes)).isEmpty();
        assertThat(stored()).isEqualTo(100);
        assertThat(foundInSieg()).isEqualTo(60);
        assertThat(control().get("total_importado")).isEqualTo(60);
        assertThat(control().get("received_stored_any_source")).isEqualTo(100);
    }

    // ============================================================================================= (2) never delivered

    @Test
    void s02_noteSiegCountsButNeverDelivers() {
        received(60, "2026-12-03");
        addNfe(SUPPLIER, cnpj, "2026-12-04T10:00:00-03:00", false);
        addNfe(SUPPLIER, cnpj, "2026-12-04T11:00:00-03:00", false);
        addNfe(SUPPLIER, cnpj, "2026-12-04T12:00:00-03:00", false);
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        days(scheduler, "2026-12-10", "2026-12-10");
        long afterDay1 = sieg.count("nfe");
        days(scheduler, "2026-12-11", "2026-12-14");
        System.out.println("QA-S02 pages day1=" + afterDay1 + " later=" + (sieg.count("nfe") - afterDay1) + " " + callsSummary());
        assertThat(stored()).isEqualTo(60);
        Map<String, Object> row = control();
        assertThat(row.get("total_sieg")).isEqualTo(63);
        assertThat(row.get("total_importado")).isEqualTo(60);
        assertThat(row.get("received_known_gap")).isEqualTo(3);
        // later days: the month is not re-read (only a bigger difference would): at most the count
        for (String day : List.of("2026-12-11", "2026-12-12", "2026-12-13", "2026-12-14")) {
            assertThat(nfeDownloads(day)).as("downloads on " + day).isZero();
        }
        // a real new note still comes; a new never-delivered one asks for one more reading, then quiet again
        Nfe fresh = addNfe(SUPPLIER, cnpj, "2026-12-14T15:00:00-03:00", true);
        days(scheduler, "2026-12-15", "2026-12-15");
        assertThat(missing(List.of(fresh))).isEmpty();
        long beforeLate = sieg.count("nfe");
        addNfe(SUPPLIER, cnpj, "2026-12-15T15:00:00-03:00", false);
        days(scheduler, "2026-12-16", "2026-12-18");
        long reads = sieg.count("nfe") - beforeLate;
        System.out.println("QA-S02 extra undeliverable -> nfe pages over 3 days=" + reads);
        assertThat(reads).as("one more reconciliation (a few pages), not every attempt").isLessThanOrEqualTo(6);
        assertThat(sieg.calls.stream().filter(c -> c.day().equals("2026-12-18") && c.kind().equals("nfe")).count()).isZero();
    }

    // ============================================================================================= (3) emitente = destinatario

    @Test
    void s03_selfNote_issuerEqualsRecipient() {
        List<Nfe> expected = new ArrayList<>(received(5, "2026-12-03"));
        Nfe self = addNfe(cnpj, cnpj, "2026-12-05T10:00:00-03:00", true);
        expected.add(self);
        expected.add(addNfe(cnpj, OTHER, "2026-12-06T10:00:00-03:00", true));
        expected.add(addNfe(cnpj, OTHER, "2026-12-07T10:00:00-03:00", true));
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        days(scheduler, "2026-12-10", "2026-12-10");
        long day1 = sieg.count("nfe");
        days(scheduler, "2026-12-11", "2026-12-14");
        assertThat(missing(expected)).isEmpty();
        assertThat(stored()).isEqualTo(8);
        System.out.println("QA-S03 pages day1=" + day1 + " later=" + (sieg.count("nfe") - day1) + " row=" + control());
        for (String day : List.of("2026-12-11", "2026-12-12", "2026-12-13", "2026-12-14")) {
            assertThat(nfeDownloads(day)).as("no eternal re-read on " + day).isZero();
        }
        // a second self note: cost bounded
        long before = sieg.count("nfe");
        Nfe self2 = addNfe(cnpj, cnpj, "2026-12-15T10:00:00-03:00", true);
        days(scheduler, "2026-12-15", "2026-12-17");
        assertThat(missing(List.of(self2))).isEmpty();
        System.out.println("QA-S03 second self note pages=" + (sieg.count("nfe") - before));
        assertThat(nfeDownloads("2026-12-17")).isZero();
    }

    // ============================================================================================= (4) month edge

    @Test
    void s04_noteAt22hOnTheLastDayOfTheMonth() {
        List<Nfe> expected = new ArrayList<>(received(3, "2026-11-10"));
        expected.add(addNfe(SUPPLIER, cnpj, "2026-11-30T22:00:00-03:00", true));   // Dec 1 01:00Z
        expected.add(addNfe(SUPPLIER, cnpj, "2026-11-30T20:00:00-03:00", true));   // Nov 30 23:00Z
        expected.add(addNfe(SUPPLIER, cnpj, "2026-12-01T00:30:00-03:00", true));   // Dec 1 03:30Z
        expected.add(addNfe(SUPPLIER, cnpj, "2026-10-31T22:00:00-03:00", true));   // Nov 1 01:00Z (local October)
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        days(scheduler, "2026-11-30", "2026-11-30");
        tick(scheduler, "2026-11-30T23:50:00");
        days(scheduler, "2026-12-01", "2026-12-05");
        System.out.println("QA-S04 " + callsSummary() + " row=" + control());
        assertThat(missing(expected)).isEmpty();
        for (String day : List.of("2026-12-03", "2026-12-04", "2026-12-05")) {
            assertThat(nfeDownloads(day)).as("no eternal re-read on " + day).isZero();
        }
        Map<String, Object> row = control();
        // the counts and what SIEG delivered use the same (UTC) edge: no false "faltam" for December
        assertThat(((Number) row.get("total_importado")).intValue()).isEqualTo(((Number) row.get("total_sieg")).intValue());
        System.out.println("QA-S04 total_sieg=" + row.get("total_sieg") + " imported=" + row.get("total_importado") + " gap="
                + row.get("received_known_gap") + " storedAny=" + row.get("received_stored_any_source"));
    }

    // ============================================================================================= (5) late arrival

    @Test
    void s05_lateArrivals() {
        List<Nfe> base = received(10, "2026-11-03");
        Nfe lateButSameMonthEnd = null;
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        days(scheduler, "2026-11-28", "2026-11-30");
        assertThat(stored()).isEqualTo(10);
        // issued Nov 29, SIEG only has it on Dec 3 (inserted in the middle of SIEG's order)
        Nfe late = addNfeAt(3, SUPPLIER, cnpj, "2026-11-29T10:00:00-03:00", true);
        days(scheduler, "2026-12-01", "2026-12-02");
        days(scheduler, "2026-12-03", "2026-12-06");
        boolean captured = missing(List.of(late)).isEmpty();
        System.out.println("QA-S05 note of November that reached SIEG in December captured=" + captured + " (window is month-to-date)");
        // late in the SAME month, inserted before the skip, then an ordinary one at the end
        Nfe lateSame = addNfeAt(2, SUPPLIER, cnpj, "2026-12-02T10:00:00-03:00", true);
        days(scheduler, "2026-12-07", "2026-12-08");
        assertThat(missing(List.of(lateSame))).as("late note of the current month, in an earlier position").isEmpty();
        // decision of 2026-11-27: the previous month is checked by the count in the first days of the month
        assertThat(captured).as("November note that reached SIEG on Dec 3").isTrue();
    }

    // ============================================================================================= (6) cancellation

    @Test
    void s06_cancellationOfOldAndRecentNotes() {
        Nfe old = addNfe(SUPPLIER, cnpj, "2026-12-08T10:00:00-03:00", true);     // 10 days old on Dec 18
        Nfe recent = addNfe(SUPPLIER, cnpj, "2026-12-17T10:00:00-03:00", true);  // 1 day old
        Nfe twoDays = addNfe(SUPPLIER, cnpj, "2026-12-16T10:00:00-03:00", true);
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        days(scheduler, "2026-12-17", "2026-12-17");
        assertThat(stored()).isEqualTo(3);
        sieg.events.put(old.key(), List.of(cancelEvent(old.key()).getBytes(StandardCharsets.UTF_8)));
        sieg.events.put(recent.key(), List.of(cancelEvent(recent.key()).getBytes(StandardCharsets.UTF_8)));
        sieg.events.put(twoDays.key(), List.of(cancelEvent(twoDays.key()).getBytes(StandardCharsets.UTF_8)));
        days(scheduler, "2026-12-18", "2026-12-19");
        Map<String, String> status = jdbc.queryForList("SELECT access_key, status FROM fiscal_documents WHERE company_id = ?", companyId)
                .stream().collect(Collectors.toMap(r -> (String) r.get("access_key"), r -> String.valueOf(r.get("status"))));
        System.out.println("QA-S06 old(10d)=" + status.get(old.key()) + " recent(1d)=" + status.get(recent.key()) + " 2d="
                + status.get(twoDays.key()) + " eventsCalls=" + sieg.calls.stream().filter(c -> c.purpose().equals("EVENTS")).count());
        assertThat(status.get(recent.key())).isEqualTo("CANCELED");
        assertThat(status.get(twoDays.key())).isEqualTo("CANCELED");
        // old note: documented, not asserted as a requirement
    }

    // ============================================================================================= (7) NFS-e shadow x off, on

    /** The same script under OFF and SHADOW: the NFS-e downloads must be identical (window, filter, skip) and the notes stored equal. */
    @Test
    void s07_shadowDownloadsExactlyLikeOff() {
        List<List<String>> nfseCalls = new ArrayList<>();
        List<Integer> storedNfse = new ArrayList<>();
        for (ServiceInvoiceCountGate gate : List.of(ServiceInvoiceCountGate.OFF, ServiceInvoiceCountGate.SHADOW)) {
            sieg.nfes.clear();
            sieg.nfses.clear();
            sieg.calls.clear();
            cleanCompanyData();
            addNfse(SUPPLIER, cnpj, "2026-11-20T10:00:00-03:00");
            FiscalXmlSyncScheduler scheduler = scheduler(gate);
            days(scheduler, "2026-12-01", "2026-12-03");
            addNfse(cnpj, OTHER, "2026-12-03T10:00:00-03:00");
            addNfse(SUPPLIER, cnpj, "2026-11-28T10:00:00-03:00");
            days(scheduler, "2026-12-04", "2026-12-08");
            nfseCalls.add(sieg.calls.stream().filter(c -> c.kind().equals("nfse"))
                    .map(c -> c.day() + " " + c.filter() + " " + c.skip() + " " + c.window()).toList());
            storedNfse.add(nfseStored());
            System.out.println("QA-S07 gate=" + gate + " nfse downloads=" + nfseCalls.getLast().size() + " stored=" + nfseStored()
                    + " counts=" + sieg.count("count"));
        }
        assertThat(nfseCalls.get(1)).as("shadow downloads exactly like off").containsExactlyElementsOf(nfseCalls.get(0));
        assertThat(storedNfse.get(1)).isEqualTo(storedNfse.get(0)).isEqualTo(3);
    }

    @Test
    void s07b_shadowLogsWhatWouldHaveHappened_andWouldHaveMissed() {
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        addNfse(SUPPLIER, cnpj, "2026-12-01T10:00:00-03:00");
        days(scheduler, "2026-12-05", "2026-12-05");                       // first run: nothing evaluated
        logs.list.clear();
        days(scheduler, "2026-12-06", "2026-12-06");                       // nothing new: would have skipped
        List<String> quiet = logsContaining("NFS-e shadow gate");
        System.out.println("QA-S07b quiet: " + quiet.stream().map(s -> s.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<id>")).toList());
        assertThat(quiet).isNotEmpty();
        assertThat(quiet.getFirst()).contains("would have skipped the month").doesNotContain("MISSED");
        // a new NFS-e shows up with the count increased: would have downloaded
        logs.list.clear();
        addNfse(SUPPLIER, cnpj, "2026-12-06T10:00:00-03:00");
        days(scheduler, "2026-12-07", "2026-12-07");
        assertThat(logsContaining("NFS-e shadow gate")).anyMatch(m -> m.contains("would have downloaded the month"));
        assertThat(logsContaining("would have MISSED")).isEmpty();
        // SIEG's count is stale: a new note shows up in the download but the count did not move: would have MISSED
        logs.list.clear();
        int countNow = sieg.nfses.size();
        sieg.nfseCountLie = countNow;
        addNfse(SUPPLIER, cnpj, "2026-12-07T11:00:00-03:00");
        days(scheduler, "2026-12-08", "2026-12-08");
        List<String> missed = logsContaining("would have MISSED");
        System.out.println("QA-S07b missed: " + missed.stream().map(s -> s.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<id>")).toList());
        assertThat(missed).hasSize(1);
        assertThat(logs.list.stream().filter(e -> e.getFormattedMessage().contains("would have MISSED")).findFirst().orElseThrow()
                .getLevel()).isEqualTo(Level.WARN);
        assertThat(nfseStored()).as("shadow still downloaded it").isEqualTo(3);
    }

    @Test
    void s07c_onMode_lateCityHallNfse_day28Arrives_day8() {
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.ON);
        addNfse(SUPPLIER, cnpj, "2026-11-10T10:00:00-03:00");
        days(scheduler, "2026-12-01", "2026-12-07");
        assertThat(nfseStored()).isEqualTo(1);
        long nfseDownloadsBefore = sieg.count("nfse");
        addNfse(SUPPLIER, cnpj, "2026-11-28T10:00:00-03:00");                   // arrives only on Dec 8
        days(scheduler, "2026-12-08", "2026-12-08");
        System.out.println("QA-S07c ON day28->day8 stored=" + nfseStored() + " downloads before=" + nfseDownloadsBefore
                + " after=" + sieg.count("nfse"));
        assertThat(nfseStored()).as("late November NFS-e is found on Dec 8 (inside the 35-day lookback)").isEqualTo(2);
        // and again quiet afterwards
        long before = sieg.count("nfse");
        days(scheduler, "2026-12-09", "2026-12-12");
        assertThat(sieg.count("nfse")).isEqualTo(before);
    }

    /** ON: an NFS-e issued on the evening of the last day (UTC = next month) delivered, then a late note of that month. */
    @Test
    void s07d_onMode_monthEdgeNfseMasksALateNote() {
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.ON);
        addNfse(SUPPLIER, cnpj, "2026-11-10T10:00:00-03:00");
        addNfse(SUPPLIER, cnpj, "2026-11-30T22:00:00-03:00");                   // Dec 1 01:00Z - SIEG counts it in DECEMBER's window
        days(scheduler, "2026-12-01", "2026-12-04");
        assertThat(nfseStored()).as("both delivered").isEqualTo(2);
        Nfse late = addNfse(SUPPLIER, cnpj, "2026-11-25T10:00:00-03:00");       // a November note that reaches SIEG on Dec 5
        days(scheduler, "2026-12-05", "2026-12-08");
        boolean captured = nfseStored() == 3;
        System.out.println("QA-S07d ON: late Nov NFS-e captured=" + captured + " stored=" + nfseStored() + " nfseDownloadsDec5to8="
                + sieg.calls.stream().filter(c -> c.kind().equals("nfse") && c.day().compareTo("2026-12-05") >= 0).count());
        assertThat(late).isNotNull();
        assertThat(captured).as("late November NFS-e must be captured even with an edge-of-month NFS-e already stored").isTrue();
    }

    // ============================================================================================= (8) reconciliation interrupted

    @Test
    void s08_reconciliationInterruptedBy5xx_keepsWhatCame_retriesTomorrow() {
        received(120, "2026-12-03");
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        days(scheduler, "2026-12-10", "2026-12-10");
        assertThat(stored()).isEqualTo(120);
        List<Nfe> late = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            late.add(addNfeAt(5 + i, SUPPLIER, cnpj, "2026-12-04T09:%02d:00-03:00".formatted(i), true));
        }
        for (int i = 0; i < 3; i++) {
            late.add(addNfeAt(70 + i, SUPPLIER, cnpj, "2026-12-04T10:%02d:00-03:00".formatted(i), true));
        }
        AtomicInteger reconcilePages = new AtomicInteger();
        sieg.failDownload = r -> ProviderCallContext.current() == br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose.RECONCILE
                && reconcilePages.incrementAndGet() == 2;
        days(scheduler, "2026-12-11", "2026-12-11");
        System.out.println("QA-S08 day1 after failure: stored=" + stored() + " reconcile calls=" + reconcilePages.get() + " late missing="
                + missing(late).size() + " gap=" + control().get("received_known_gap"));
        assertThat(reconcilePages.get()).as("only the first attempt of the day reconciles").isEqualTo(2);
        assertThat(stored()).as("page 0 of the pass kept the first late notes").isGreaterThan(120);
        sieg.failDownload = r -> false;
        days(scheduler, "2026-12-12", "2026-12-12");
        assertThat(missing(late)).as("the next day completes the pass").isEmpty();
        assertThat(stored()).isEqualTo(127);
        long pagesAfter = sieg.calls.stream().filter(c -> c.day().equals("2026-12-12") && c.kind().equals("nfe")).count();
        System.out.println("QA-S08 next day nfe pages=" + pagesAfter);
        days(scheduler, "2026-12-13", "2026-12-14");
        assertThat(nfeDownloads("2026-12-13")).isZero();
    }

    // ============================================================================================= (9) two instances

    @Test
    void s09_twoInstancesDoNotDuplicate() throws Exception {
        received(80, "2026-12-03");
        FiscalXmlSyncScheduler a = scheduler(ServiceInvoiceCountGate.SHADOW);
        FiscalXmlSyncScheduler b = scheduler(ServiceInvoiceCountGate.SHADOW);
        for (int round = 0; round < 6; round++) {
            LocalDateTime when = LocalDateTime.of(2026, 12, 10, 6 + round, 0);
            clock.set(when);
            sieg.day = "2026-12-10";
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            Thread ta = new Thread(() -> {
                await(start);
                a.syncAllCompanies();
            });
            Thread tb = new Thread(() -> {
                await(start);
                b.syncAllCompanies();
            });
            ta.start();
            tb.start();
            start.countDown();
            ta.join();
            tb.join();
        }
        assertThat(stored()).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT access_key FROM fiscal_documents WHERE company_id = ?"
                + " GROUP BY access_key HAVING COUNT(*) > 1) d", Integer.class, companyId)).isZero();
        long pages = sieg.count("nfe");
        System.out.println("QA-S09 two instances, 6 rounds: nfe pages=" + pages + " counts=" + sieg.count("count"));
        // one instance's reconciliation failure: the other instance may retry the same day (in-memory memory) - documented
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================================================= (10) Sincronizar agora

    @Test
    void s10_syncNowForcesRecount() {
        received(10, "2026-12-03");
        FiscalXmlSyncScheduler scheduler = scheduler(ServiceInvoiceCountGate.SHADOW);
        tick(scheduler, "2026-12-10T08:00:00");
        assertThat(stored()).isEqualTo(10);
        long counts = sieg.count("count");
        tick(scheduler, "2026-12-10T09:00:00");
        assertThat(sieg.count("count")).as("up to date: no call").isEqualTo(counts);
        // zero-total side (issued) was counted at 08:00; at 09:00 it is not recounted (6 h)
        Nfe fresh = addNfe(SUPPLIER, cnpj, "2026-12-10T08:30:00-03:00", true);
        tick(scheduler, "2026-12-10T10:00:00");
        assertThat(missing(List.of(fresh))).as("received side up to date today -> waits until tomorrow without sync-now").hasSize(1);
        jdbc.update("INSERT INTO capture_priority_requests (id, company_id, accounting_firm_id, requested_by, requested_scope,"
                        + " requested_at, status, open_key, deleted, created_by, created_date) VALUES (?, ?, ?, ?, 'FIRM', ?, 'PENDING', ?, 0, 'qa', ?)",
                UUID.randomUUID().toString(), companyId, firmId, UUID.randomUUID().toString(), Timestamp.valueOf("2026-12-10 10:30:00"),
                companyId, Timestamp.valueOf("2026-12-10 10:30:00"));
        sieg.calls.clear();
        tick(scheduler, "2026-12-10T11:00:00");
        System.out.println("QA-S10 sync-now calls: " + sieg.calls.stream().map(c -> c.kind() + "/" + c.filter() + "/" + c.purpose()).toList());
        assertThat(missing(List.of(fresh))).as("the sync-now recount brings the note").isEmpty();
        assertThat(sieg.calls.stream().map(Call::purpose).distinct().toList()).containsOnly("PRIORITY");
        // a side with total 0 (issued) recounts after 6 h without sync-now
        sieg.calls.clear();
        tick(scheduler, "2026-12-10T17:30:00");
        System.out.println("QA-S10 17:30 calls: " + sieg.calls.stream().map(c -> c.kind() + "/" + c.filter()).toList());
        assertThat(sieg.calls.stream().anyMatch(c -> c.filter().equals("I") && c.kind().equals("count"))).as("zero side recounted after 6 h").isTrue();
        // the 24 h edge: the checked side is not recounted at 5h59 but is at 6h
        jdbc.update("UPDATE capture_priority_requests SET status = 'SERVED' WHERE company_id = ?", companyId);
    }

    // ============================================================================================= fixtures

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

    /** Clears what the sync wrote for the company (notes, control row, requests), keeps the company. */
    private void cleanCompanyData() {
        jdbc.update("DELETE i FROM fiscal_document_items i JOIN fiscal_documents d ON d.id = i.document_id WHERE d.company_id = ?",
                companyId);
        for (int pass = 0; pass < 3; pass++) {
            for (String table : jdbc.queryForList("SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema = DATABASE()"
                    + " AND column_name = 'company_id' AND table_name NOT IN ('companies', 'subscriptions')", String.class)) {
                try {
                    jdbc.update("DELETE FROM `" + table + "` WHERE company_id = ?", companyId);
                } catch (DataAccessException ignored) {
                    // a child table first - the next pass
                }
            }
        }
    }

    private void cleanUp() {
        if (firmId == null) {
            return;
        }
        cleanCompanyData();
        for (String planId : jdbc.queryForList("SELECT id FROM plans WHERE accounting_firm_id = ?", String.class, firmId)) {
            jdbc.update("DELETE FROM subscriptions WHERE plan_id = ?", planId);
            jdbc.update("DELETE FROM plan_services WHERE plan_id = ?", planId);
            jdbc.update("DELETE FROM plans WHERE id = ?", planId);
        }
        jdbc.update("DELETE FROM subscriptions WHERE company_id = ?", companyId);
        jdbc.update("DELETE FROM firm_integrations WHERE accounting_firm_id = ?", firmId);
        jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ?", firmId);
        String documentId = jdbc.queryForObject("SELECT document_id FROM companies WHERE id = ?", String.class, companyId);
        jdbc.update("DELETE FROM companies WHERE id = ?", companyId);
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firmId);
        firmId = null;
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
