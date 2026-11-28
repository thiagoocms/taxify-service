package br.com.cshub.taxify.service.audit;

import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.dto.assessment.AssessmentAlertDTO;
import br.com.cshub.taxify.dto.assessment.MonthRevenueDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.service.assessment.TaxAssessmentService;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckEngine;
import br.com.cshub.taxify.service.fiscalcheck.FiscalDivergence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditEngineTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 10);

    @Mock
    private GoodsInvoiceRepository documentRepository;
    @Mock
    private FiscalDocumentRepository anyDocumentRepository;
    @Mock
    private SefazNoteSummaryRepository summaryRepository;
    @Mock
    private FiscalCheckEngine checkEngine;
    @Mock
    private TaxAssessmentService assessmentService;
    @Mock
    private FiscalXmlFileStorage fileStorage;

    private AuditEngine engine;
    private Company company;
    private final List<GoodsInvoice> notes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        engine = new AuditEngine(documentRepository, anyDocumentRepository, summaryRepository, checkEngine, assessmentService,
                fileStorage);
        company = TestNotes.company(TestNotes.COMPANY_CNPJ);
        when(documentRepository.findIssuedBetween(eq(company.getId()), any(), any()))
                .thenReturn(notes);
        when(summaryRepository.findByCompany_IdAndCompletedAtIsNullAndIssueDateGreaterThanEqualAndIssueDateLessThan(any(), any(), any())).thenReturn(List.of());
        when(checkEngine.check(any(), any())).thenReturn(List.of());
        when(checkEngine.checkDocument(any(), any(), any())).thenReturn(List.of());
        when(assessmentService.assess(any(), anyString())).thenReturn(assessment(BigDecimal.TEN, List.of(), List.of()));
    }

    private GoodsInvoice issued(String model, String serie, String numero, String key) {
        GoodsInvoice note = TestNotes.note(company).ownIssue().model(model).series(serie).number(numero).key(key).build();
        notes.add(note);
        return note;
    }

    private static TaxAssessmentDTO assessment(BigDecimal net, List<MonthRevenueDTO> history, List<AssessmentAlertDTO> alerts) {
        MonthRevenueDTO revenue = new MonthRevenueDTO(MONTH.toString(), net, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, net);
        return new TaxAssessmentDTO(null, null, null, MONTH.toString(), null, null, null, null, revenue, 0,
                new BigDecimal("3500000"), false, null, List.of(), BigDecimal.ZERO, alerts, history, null, null);
    }

    private List<AuditRuleDefinition> rules() {
        return engine.audit(company, MONTH).findings().stream().map(AuditEngine.Finding::rule).toList();
    }

    @Test
    void aCleanMonthHasNoFinding() {
        issued("55", "1", "10", "K10");
        issued("55", "1", "11", "K11");

        assertThat(engine.audit(company, MONTH).findings()).isEmpty();
    }

    @Test
    void numberingGapsAndRepeatedNumbersPerSeries() {
        issued("55", "1", "10", "K10");
        issued("55", "1", "13", "K13");
        issued("55", "1", "13", "K13-OTHER");
        issued("55", "2", "1", "S2"); // another series - no gap with series 1
        issued("65", "1", "500", "C500");
        issued("65", "1", "501", "C501");

        List<AuditEngine.Finding> findings = engine.audit(company, MONTH).findings();

        assertThat(findings).extracting(AuditEngine.Finding::rule)
                .containsExactlyInAnyOrder(AuditRuleDefinition.NUMERO_DUPLICADO, AuditRuleDefinition.NUMERACAO_LACUNA);
        AuditEngine.Finding gap = findings.stream().filter(f -> f.rule() == AuditRuleDefinition.NUMERACAO_LACUNA).findFirst().orElseThrow();
        assertThat(gap.title()).isEqualTo("NF-e série 1: 2 número(s) sem nota entre 10 e 13");
        assertThat(gap.detail()).startsWith("Faltam: 11, 12.");
        AuditEngine.Finding duplicated = findings.stream().filter(f -> f.rule() == AuditRuleDefinition.NUMERO_DUPLICADO).findFirst().orElseThrow();
        assertThat(duplicated.title()).contains("nº 13 com 2 chaves");
    }

    @Test
    void aReturnWithoutTheOriginalNote() throws Exception {
        GoodsInvoice withoutRef = issued("55", "1", "20", "K20");
        TestNotes.withPurpose(withoutRef, "4");
        withoutRef.setXmlFilePath("a.xml");
        GoodsInvoice unknownRef = issued("55", "1", "21", "K21");
        TestNotes.withPurpose(unknownRef, "4");
        unknownRef.setXmlFilePath("b.xml");
        GoodsInvoice knownRef = issued("55", "1", "22", "K22");
        TestNotes.withPurpose(knownRef, "4");
        knownRef.setXmlFilePath("c.xml");
        when(fileStorage.read("a.xml")).thenReturn("<NFe><infNFe><ide><finNFe>4</finNFe></ide></infNFe></NFe>".getBytes(StandardCharsets.UTF_8));
        when(fileStorage.read("b.xml")).thenReturn("<NFe><ide><NFref><refNFe>UNKNOWN</refNFe></NFref></ide></NFe>".getBytes(StandardCharsets.UTF_8));
        when(fileStorage.read("c.xml")).thenReturn("<NFe><ide><NFref><refNFe>KNOWN</refNFe></NFref></ide></NFe>".getBytes(StandardCharsets.UTF_8));
        when(anyDocumentRepository.existsByCompany_IdAndAccessKey(company.getId(), "KNOWN")).thenReturn(true);

        List<AuditEngine.Finding> findings = engine.audit(company, MONTH).findings();

        assertThat(findings).extracting(AuditEngine.Finding::rule).containsOnly(AuditRuleDefinition.DEVOLUCAO_SEM_ORIGINAL);
        assertThat(findings).extracting(AuditEngine.Finding::objectId)
                .containsExactlyInAnyOrder(withoutRef.getId().toString(), unknownRef.getId().toString());
    }

    @Test
    void entriesLeftOnlyWithTheSummary() {
        SefazNoteSummary old = new SefazNoteSummary();
        old.setIssuerName("FORNECEDOR");
        org.springframework.test.util.ReflectionTestUtils.setField(old, "createdDate", LocalDateTime.now().minusDays(30));
        SefazNoteSummary recent = new SefazNoteSummary();
        org.springframework.test.util.ReflectionTestUtils.setField(recent, "createdDate", LocalDateTime.now().minusDays(2));
        when(summaryRepository.findByCompany_IdAndCompletedAtIsNullAndIssueDateGreaterThanEqualAndIssueDateLessThan(eq(company.getId()), any(), any()))
                .thenReturn(List.of(old, recent));

        List<AuditEngine.Finding> findings = engine.audit(company, MONTH).findings();

        assertThat(findings).extracting(AuditEngine.Finding::rule).containsExactly(AuditRuleDefinition.NOTA_SO_RESUMO);
        assertThat(findings.getFirst().title()).startsWith("1 entrada(s)");
    }

    @Test
    void itemChecksOfTheIssuedNotesBecomeOneFindingPerRule() {
        GoodsInvoice first = issued("55", "1", "30", "K30");
        GoodsInvoice second = issued("55", "1", "31", "K31");
        FiscalDocumentItem item = mock(FiscalDocumentItem.class);
        first.addItem(item);
        second.addItem(item);
        FiscalDivergence cfop = new FiscalDivergence("CFOP_DESTINATION", "CFOP", "5102", "6102", DivergenceSeverity.WARNING, "CFOP interno numa venda interestadual");
        FiscalDivergence unrelated = new FiscalDivergence("TAX_CALCULATION", "ICMS", "1", "2", DivergenceSeverity.ERROR, "cálculo");
        when(checkEngine.check(eq(item), any())).thenReturn(List.of(cfop, unrelated));

        List<AuditEngine.Finding> findings = engine.audit(company, MONTH).findings();

        assertThat(findings).extracting(AuditEngine.Finding::rule).containsExactly(AuditRuleDefinition.CFOP_DESTINO);
        assertThat(findings.getFirst().title()).startsWith("2 nota(s) emitida(s)");
        assertThat(findings.getFirst().detail()).contains("nº 30, nº 31");
    }

    @Test
    void canceledNotesAreNotChecked() {
        GoodsInvoice canceled = issued("55", "1", "40", "K40");
        canceled.cancel(null);
        FiscalDocumentItem item = mock(FiscalDocumentItem.class);
        canceled.addItem(item);
        when(checkEngine.check(eq(item), any())).thenReturn(List.of(
                new FiscalDivergence("CRT_CST", "CST_ICMS", "00", "CSOSN", DivergenceSeverity.ERROR, "x")));

        assertThat(rules()).isEmpty();
    }

    @Test
    void anomaliesRevenueSpikeNfceCancellationsAndTheSimplesLimits() {
        List<MonthRevenueDTO> history = List.of(
                new MonthRevenueDTO("2026-07", null, null, null, null, new BigDecimal("1000")),
                new MonthRevenueDTO("2026-08", null, null, null, null, new BigDecimal("1000")),
                new MonthRevenueDTO("2026-09", null, null, null, null, new BigDecimal("1000")),
                new MonthRevenueDTO("2026-10", null, null, null, null, new BigDecimal("5000")));
        when(assessmentService.assess(company.getId(), "2026-10")).thenReturn(assessment(new BigDecimal("5000"), history,
                List.of(new AssessmentAlertDTO(AssessmentAlertDTO.Level.WARNING, "SIMPLES_SUBLIMIT", "RBT12 acima do sublimite"))));
        for (int number = 1; number <= 20; number++) {
            GoodsInvoice nfce = issued("65", "1", String.valueOf(number), "C" + number);
            if (number <= 2) {
                nfce.cancel(null);
            }
        }

        List<AuditEngine.Finding> findings = engine.audit(company, MONTH).findings();

        assertThat(findings).extracting(AuditEngine.Finding::rule).containsExactlyInAnyOrder(
                AuditRuleDefinition.FATURAMENTO_ATIPICO, AuditRuleDefinition.NFCE_CANCELAMENTO, AuditRuleDefinition.RBT12_SUBLIMITE);
        assertThat(findings.stream().filter(f -> f.rule() == AuditRuleDefinition.NFCE_CANCELAMENTO).findFirst().orElseThrow().title())
                .isEqualTo("2 de 20 NFC-e canceladas no mês (10,0%)");
    }
}
