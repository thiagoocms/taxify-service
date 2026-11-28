package br.com.cshub.taxify.service.retention;

import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.Nfse;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.integration.nfse.NfseXml;
import br.com.cshub.taxify.integration.nfse.NfseXmlTest;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.IssTax;
import br.com.cshub.taxify.domain.fiscaldocument.Withholdings;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsTotals;
import br.com.cshub.taxify.mapper.fiscaldocument.FiscalDocumentFactory;
import br.com.cshub.taxify.repository.NfseRepository;
import br.com.cshub.taxify.service.CompanyTaxRegimeService;
import br.com.cshub.taxify.service.retention.RetentionCalculator.Nature;
import br.com.cshub.taxify.service.retention.RetentionCalculator.Retention;
import br.com.cshub.taxify.service.retention.RetentionCalculator.Tax;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetentionCalculatorTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @Mock
    private NfseRepository serviceInvoiceRepository;
    @Mock
    private GoodsInvoiceRepository documentRepository;
    @Mock
    private CompanyTaxRegimeService regimeService;

    private RetentionCalculator calculator;
    /** Takes the national NFS-e (CNPJ of its tomador) and buys goods with ICMS-ST - Lucro Presumido. */
    private Company taker;
    /** Provides the ABRASF NFS-e (CNPJ of its prestador) - Simples Nacional. */
    private Company provider;

    @BeforeEach
    void setUp() {
        calculator = new RetentionCalculator(serviceInvoiceRepository, documentRepository, regimeService);
        taker = company("99888777000166", TaxRegime.LUCRO_PRESUMIDO);
        provider = company("55444333000122", TaxRegime.SIMPLES_NACIONAL);
        when(regimeService.regimesAt(any(), any())).thenReturn(Map.of());
        when(documentRepository.findForRetentions(any(), any(), any())).thenReturn(List.of());
        when(serviceInvoiceRepository.findForRetentions(any(), any(), any())).thenReturn(List.of());
    }

    private static Company company(String cnpj, TaxRegime regime) {
        Company company = new Company(UUID.randomUUID());
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        company.setName("EMPRESA " + cnpj);
        company.setTaxRegimeType(regime);
        return company;
    }

    private static Nfse invoice(Company company, String xml) {
        NfseXml nfse = NfseXml.parse(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();
        Nfse invoice = FiscalDocumentFactory.fromParsed(nfse, company, DocumentSourceKey.SIEG, "p.xml");
        invoice.setId(UUID.randomUUID());
        invoice.setCompetence(NOVEMBER.atDay(1));
        return invoice;
    }

    private static BigDecimal total(List<Retention> retentions, Nature nature) {
        return retentions.stream().filter(r -> r.nature() == nature).map(Retention::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The roadmap's acceptance case: R$ 221,50 to pay, R$ 70,00 to offset, R$ 120,00 informative, 1 undue retention. */
    @Test
    void theRoadmapAcceptanceCase() {
        Nfse taken = invoice(taker, NfseXmlTest.national("2", "1"));
        Nfse provided = invoice(provider, NfseXmlTest.ABRASF);
        GoodsInvoice purchase = TestNotes.note(taker).number("900").issuer(TestNotes.SUPPLIER_CNPJ, "DISTRIBUIDORA").build();
        purchase.setGoodsTotals(new GoodsTotals(null, null, null, null, null, new BigDecimal("120.00"), null, null, null, null, null,
                null, null, null, null, null, null));
        when(serviceInvoiceRepository.findForRetentions(any(), any(), any())).thenReturn(List.of(taken, provided));
        when(documentRepository.findForRetentions(any(), any(), any())).thenReturn(List.of(purchase));

        RetentionCalculator.Result result = calculator.compute(List.of(taker, provider), NOVEMBER);

        assertThat(total(result.retentions(), Nature.A_RECOLHER)).isEqualByComparingTo("221.50");
        assertThat(total(result.retentions(), Nature.A_COMPENSAR)).isEqualByComparingTo("70.00");
        assertThat(total(result.retentions(), Nature.INFORMATIVO)).isEqualByComparingTo("120.00");
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().getFirst().kind()).isEqualTo(RetentionCalculator.Issue.UNDUE);
        assertThat(result.issues().getFirst().company()).isSameAs(provider);
    }

    @Test
    void whatToDoWithEachTaxWithheldByTheCompany() {
        when(serviceInvoiceRepository.findForRetentions(any(), any(), any()))
                .thenReturn(List.of(invoice(taker, NfseXmlTest.national("2", "1"))));

        List<Retention> retentions = calculator.compute(List.of(taker), NOVEMBER).retentions();

        Retention irrf = retentions.stream().filter(r -> r.tax() == Tax.IRRF).findFirst().orElseThrow();
        assertThat(irrf.action()).contains("DARF 1708");
        assertThat(irrf.dueDate()).isEqualTo(LocalDate.of(2026, 12, 18)); // 20/12/2026 is a Sunday
        assertThat(irrf.counterpartName()).isEqualTo("PRESTADORA LTDA");
        assertThat(retentions.stream().filter(r -> r.tax() == Tax.CSLL).findFirst().orElseThrow().action()).contains("DARF 5952");
        assertThat(retentions.stream().filter(r -> r.tax() == Tax.INSS).findFirst().orElseThrow().action()).contains("R-2010");
        Retention iss = retentions.stream().filter(r -> r.tax() == Tax.ISS).findFirst().orElseThrow();
        assertThat(iss.dueDate()).isNull();
        assertThat(iss.action()).contains("2611606");
    }

    @Test
    void pisCofinsOnlyInformedAreNotRetentions() {
        when(serviceInvoiceRepository.findForRetentions(any(), any(), any()))
                .thenReturn(List.of(invoice(taker, NfseXmlTest.national("1", "2"))));

        List<Retention> retentions = calculator.compute(List.of(taker), NOVEMBER).retentions();

        assertThat(retentions).extracting(Retention::tax).containsExactlyInAnyOrder(Tax.IRRF, Tax.CSLL, Tax.INSS);
    }

    @Test
    void aProviderOutsideTheSimplesOffsetsByItsRegime() {
        provider.setTaxRegimeType(TaxRegime.LUCRO_REAL);
        when(serviceInvoiceRepository.findForRetentions(any(), any(), any())).thenReturn(List.of(invoice(provider, NfseXmlTest.ABRASF)));

        RetentionCalculator.Result result = calculator.compute(List.of(provider), NOVEMBER);

        assertThat(result.issues()).isEmpty();
        assertThat(result.retentions().stream().filter(r -> r.tax() == Tax.IRRF).findFirst().orElseThrow().action())
                .contains("IRPJ");
    }

    @Test
    void issRetainedWithoutValueIsAnIssue() {
        Nfse invoice = invoice(taker, NfseXmlTest.national("2", "1"));
        IssTax iss = invoice.getIss();
        invoice.setIss(new IssTax(iss.base(), iss.rate(), iss.amount(), iss.withheld(), null));
        when(serviceInvoiceRepository.findForRetentions(any(), any(), any())).thenReturn(List.of(invoice));

        RetentionCalculator.Result result = calculator.compute(List.of(taker), NOVEMBER);

        assertThat(result.retentions()).extracting(Retention::tax).doesNotContain(Tax.ISS);
        assertThat(result.issues()).extracting(RetentionCalculator.Issue::kind).containsExactly(RetentionCalculator.Issue.ISS_WITHOUT_VALUE);
    }

    @Test
    void retentionsOfAGoodsNote() {
        GoodsInvoice sale = TestNotes.note(taker).ownIssue().build();
        sale.setWithholdings(new Withholdings(new BigDecimal("6.50"), null, null, new BigDecimal("15.00"), null));
        when(documentRepository.findForRetentions(any(), any(), any())).thenReturn(List.of(sale));

        List<Retention> retentions = calculator.compute(List.of(taker), NOVEMBER).retentions();

        assertThat(retentions).extracting(Retention::nature).containsOnly(Nature.A_COMPENSAR);
        assertThat(retentions).extracting(Retention::documentType).containsOnly("NFE");
        assertThat(total(retentions, Nature.A_COMPENSAR)).isEqualByComparingTo("21.50");
    }

    @Test
    void federalDueDateMovesBackFromTheWeekend() {
        assertThat(RetentionCalculator.federalDueDate(YearMonth.of(2026, 10))).isEqualTo(LocalDate.of(2026, 11, 20)); // Friday
        assertThat(RetentionCalculator.federalDueDate(YearMonth.of(2026, 5))).isEqualTo(LocalDate.of(2026, 6, 19)); // 20/06 Saturday
    }
}
