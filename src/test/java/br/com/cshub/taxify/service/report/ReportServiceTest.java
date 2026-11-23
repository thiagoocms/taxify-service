package br.com.cshub.taxify.service.report;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.assessment.MonthRevenueDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.dto.assessment.TaxEstimateDTO;
import br.com.cshub.taxify.dto.report.ReportDTOs;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.service.assessment.TaxAssessmentService;
import br.com.cshub.taxify.service.retention.RetentionCalculator;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    @Mock
    private TaxAssessmentService assessmentService;
    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private FiscalXmlDocumentItemRepository itemRepository;
    @Mock
    private PendencyRepository pendencyRepository;
    @Mock
    private RetentionCalculator retentionCalculator;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private MessageService messageService;

    private ReportService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new ReportService(assessmentService, documentRepository, itemRepository, pendencyRepository, retentionCalculator,
                companyValidation, messageService);
        company = new Company(UUID.randomUUID());
        company.setName("EMPRESA");
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        when(documentRepository.totalsByDirection(eq(company.getId()), any(), any())).thenReturn(List.of());
        when(itemRepository.sumByCompanyMonthAndCfop(any(), any(), any())).thenReturn(List.of());
        when(pendencyRepository.countOpen(any())).thenReturn(List.of());
        when(retentionCalculator.compute(any(), any())).thenReturn(new RetentionCalculator.Result(List.of(), List.of()));
    }

    private static TaxAssessmentDTO month(String month, BigDecimal net, BigDecimal services, List<TaxEstimateDTO> taxes) {
        MonthRevenueDTO revenue = new MonthRevenueDTO(month, BigDecimal.ZERO, net.subtract(services), services, BigDecimal.ZERO, net);
        BigDecimal total = taxes.stream().map(TaxEstimateDTO::amount).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TaxAssessmentDTO(null, "EMPRESA", null, month, null, "Simples Nacional", null, null, revenue, 1,
                BigDecimal.ZERO, false, BigDecimal.ZERO, taxes, total, List.of(), List.of(), null, null);
    }

    private static TaxEstimateDTO tax(String code, String amount) {
        return new TaxEstimateDTO(code, code, null, null, new BigDecimal(amount), null);
    }

    @Test
    void curvaAbcClassifiesByAccumulatedShare() {
        List<Object[]> rows = List.of(
                new Object[]{"P1", "Produto 1", "11111111", new BigDecimal("700"), 3L},
                new Object[]{"P2", "Produto 2", "22222222", new BigDecimal("150"), 2L},
                new Object[]{"P3", "Produto 3", null, new BigDecimal("100"), 1L},
                new Object[]{"P4", "Produto 4", null, new BigDecimal("50"), 1L});

        ReportDTOs.Abc abc = ReportService.abc("PRODUTO", YearMonth.of(2025, 11), YearMonth.of(2026, 10), rows, false);

        assertThat(abc.total()).isEqualByComparingTo("1000");
        assertThat(abc.items()).extracting(ReportDTOs.AbcItem::curve).containsExactly("A", "A", "B", "C");
        assertThat(abc.countA()).isEqualTo(2);
        assertThat(abc.items().get(1).accumulated()).isEqualByComparingTo("85");
        assertThat(abc.items().getFirst().detail()).isEqualTo("NCM 11111111");
    }

    @Test
    void curvaAbcBySupplier() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"99888777000166", "FORNECEDOR A", new BigDecimal("900"), 4L});
        rows.add(new Object[]{"11222333000181", "FORNECEDOR B", new BigDecimal("100"), 1L});

        ReportDTOs.Abc abc = ReportService.abc("FORNECEDOR", YearMonth.of(2026, 10), YearMonth.of(2026, 10), rows, true);

        assertThat(abc.items()).extracting(ReportDTOs.AbcItem::name).containsExactly("FORNECEDOR A", "FORNECEDOR B");
        assertThat(abc.items()).extracting(ReportDTOs.AbcItem::curve).containsExactly("A", "B"); // B starts at 90% - under 95%
        assertThat(abc.items().getFirst().notes()).isEqualTo(4);
    }

    @Test
    void simulationAddsTheTaxesOutsideTheSimplesAndMarksTheCheapest() {
        Map<TaxRegime, List<TaxAssessmentDTO>> results = new LinkedHashMap<>();
        // 12 months of R$ 10.000 (R$ 4.000 of services) - R$ 120.000 a year
        List<TaxAssessmentDTO> simples = new ArrayList<>();
        List<TaxAssessmentDTO> presumido = new ArrayList<>();
        List<TaxAssessmentDTO> real = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String m = YearMonth.of(2025, 10).plusMonths(i).toString();
            simples.add(month(m, new BigDecimal("10000"), new BigDecimal("4000"), List.of(tax("DAS_I", "400"), tax("DAS_III", "240"))));
            presumido.add(month(m, new BigDecimal("10000"), new BigDecimal("4000"), List.of(tax("PIS", "65"), tax("COFINS", "300"),
                    tax("IRPJ", "264"), tax("CSLL", "158.40"))));
            real.add(month(m, new BigDecimal("10000"), new BigDecimal("4000"), List.of(tax("PIS", "100"), tax("COFINS", "460"),
                    new TaxEstimateDTO("IRPJ_CSLL", "IRPJ e CSLL", null, null, null, null))));
        }
        results.put(TaxRegime.SIMPLES_NACIONAL, simples);
        results.put(TaxRegime.LUCRO_PRESUMIDO, presumido);
        results.put(TaxRegime.LUCRO_REAL, real);
        when(assessmentService.simulate(eq(company), eq(YearMonth.of(2026, 10)), any())).thenReturn(results);
        when(assessmentService.assess(eq(company.getId()), anyString())).thenReturn(simples.getLast());
        when(documentRepository.totalsByDirection(eq(company.getId()), any(), any())).thenReturn(List.of(
                new Object[]{FiscalDirection.OUTBOUND, 12L, new BigDecimal("120000"), new BigDecimal("10000"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L},
                new Object[]{FiscalDirection.INBOUND, 12L, new BigDecimal("60000"), new BigDecimal("4000"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L}));

        ReportDTOs.Simulation simulation = service.simulate(company.getId().toString(), "2026-10", null, null);

        assertThat(simulation.revenue()).isEqualByComparingTo("120000");
        assertThat(simulation.from()).isEqualTo("2025-11");
        ReportDTOs.RegimeResult simplesResult = simulation.regimes().get(0);
        assertThat(simplesResult.total()).isEqualByComparingTo("7680");          // 640 x 12, ICMS/ISS already in the DAS
        assertThat(simplesResult.effectiveRate()).isEqualByComparingTo("6.40");
        assertThat(simplesResult.taxes()).extracting(ReportDTOs.TaxLine::code).containsExactly("DAS");
        assertThat(simplesResult.cheapest()).isTrue();
        ReportDTOs.RegimeResult presumidoResult = simulation.regimes().get(1);
        // 787,40 x 12 + ISS 5% of 48.000 (2.400) + ICMS balance 6.000
        assertThat(presumidoResult.total()).isEqualByComparingTo("17848.80");
        assertThat(presumidoResult.taxes()).extracting(ReportDTOs.TaxLine::code).contains("ISS", "ICMS");
        ReportDTOs.RegimeResult realResult = simulation.regimes().get(2);
        // 560 x 12 + ISS 2.400 + ICMS 6.000 + IRPJ 15% and CSLL 9% of 10% of 120.000
        assertThat(realResult.total()).isEqualByComparingTo("18000.00");
        assertThat(realResult.notes()).anyMatch(note -> note.contains("10% da receita"));
    }

    @Test
    void theMonthlyReportPdfIsGenerated() {
        when(assessmentService.assess(eq(company.getId()), eq("2026-10"))).thenReturn(
                month("2026-10", new BigDecimal("10000"), BigDecimal.ZERO, List.of(tax("DAS_I", "400"))));

        ReportDTOs.Monthly report = service.monthly(company.getId().toString(), "2026-10");
        byte[] pdf = ReportPdf.monthly(report);

        assertThat(report.totalTaxes()).isEqualByComparingTo("400");
        assertThat(report.entries().notes()).isZero();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }
}
