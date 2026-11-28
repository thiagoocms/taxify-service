package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.reform.IbsCbsRatePolicy;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIbsCbs;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import br.com.cshub.taxify.enumerated.IbsCbsRateStatus;
import br.com.cshub.taxify.repository.IbsCbsRateRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * AL-RN-02 (spec 2026-11-27, F1a-0): only LAW years enter the conferência's context, in one query; the rate check of
 * a year without LAW doesn't run. The 2027 rates are fictitious test numbers (CT-AL-04 and CT-AL-05).
 */
class IbsCbsRateStatusCheckTest {

    private final ProductFiscalRuleRepository ruleRepository = mock(ProductFiscalRuleRepository.class);
    private final IbsCbsRateRepository rateRepository = mock(IbsCbsRateRepository.class);
    private final IbsCbsItemCheck itemCheck = new IbsCbsItemCheck();
    private final FiscalCheckEngine engine = new FiscalCheckEngine(List.of(itemCheck), List.of(), ruleRepository, rateRepository);
    private final UUID companyId = UUID.randomUUID();

    private static IbsCbsRate law2026() {
        return new IbsCbsRate(2026, new BigDecimal("0.9000"), new BigDecimal("0.1000"), new BigDecimal("0.0000"));
    }

    private static IbsCbsRate law2027() {
        return new IbsCbsRate(2027, IbsCbsRatePolicy.normalize(IbsCbsRateStatus.LAW, new BigDecimal("8.8"),
                new BigDecimal("0.1"), BigDecimal.ZERO, "Ato fictício", "https://exemplo.test/ato"));
    }

    /** CT-AL-20: two queries (rules and rates); the rates query is the LAW-only one, never findAll. */
    @Test
    void contextLoadsOnlyTheLawYearsInOneQuery() {
        when(rateRepository.findFindingRates()).thenReturn(List.of(law2026()));

        FiscalCheckContext context = engine.contextFor(companyId);

        assertThat(context.ibsCbsRateFor(2026)).isPresent();
        assertThat(context.ibsCbsRateFor(2027)).isEmpty();
        verify(ruleRepository, times(1)).findByCompany_IdAndDeletedIsFalse(companyId);
        verify(rateRepository, times(1)).findFindingRates();
        verify(rateRepository, never()).findAll();
        verify(rateRepository, never()).findAllYears();
        verifyNoMoreInteractions(rateRepository);
    }

    /** CA-AL-01 and CT-AL-02: 2026 keeps comparing as before; the last minute of 2026 is still 2026. */
    @Test
    void year2026StillCompares() {
        when(rateRepository.findFindingRates()).thenReturn(List.of(law2026()));
        FiscalCheckContext context = engine.contextFor(companyId);

        FiscalDocumentItem correct = item(LocalDateTime.of(2026, 12, 31, 23, 59), "000", "0.9", "0.1", "0");
        assertThat(itemCheck.check(correct, context)).isEmpty();

        FiscalDocumentItem wrong = item(LocalDateTime.of(2026, 6, 1, 10, 0), "000", "1", "0.1", "0");
        assertThat(itemCheck.check(wrong, context)).filteredOn(d -> d.code().equals(IbsCbsItemCheck.RATE))
                .singleElement().satisfies(divergence -> {
                    assertThat(divergence.expected()).isEqualTo("0.9");
                    assertThat(divergence.severity()).isEqualTo(DivergenceSeverity.ERROR);
                });
    }

    /** CT-AL-03 and CT-AL-04: no LAW row for 2027 (none, or ESTIMATE left out by the query): no IBSCBS_RATE. */
    @Test
    void year2027WithoutLawHasNoRateFinding() {
        when(rateRepository.findFindingRates()).thenReturn(List.of(law2026()));
        FiscalCheckContext context = engine.contextFor(companyId);

        FiscalDocumentItem note2027 = item(LocalDateTime.of(2027, 1, 1, 0, 0), "000", "5.0", "0.1", "0");
        assertThat(itemCheck.check(note2027, context)).extracting(FiscalDivergence::code).doesNotContain(IbsCbsItemCheck.RATE);
    }

    /** CA-AL-03 and CT-AL-05: 2027 as LAW - ERROR with the current message citing 2027. */
    @Test
    void year2027AsLawCompares() {
        when(rateRepository.findFindingRates()).thenReturn(List.of(law2026(), law2027()));
        FiscalCheckContext context = engine.contextFor(companyId);

        FiscalDocumentItem note2027 = item(LocalDateTime.of(2027, 1, 1, 0, 0), "000", "5.0", "0.1", "0");
        assertThat(itemCheck.check(note2027, context)).filteredOn(d -> d.code().equals(IbsCbsItemCheck.RATE))
                .singleElement().satisfies(divergence -> {
                    assertThat(divergence.field()).isEqualTo("pCBS");
                    assertThat(divergence.severity()).isEqualTo(DivergenceSeverity.ERROR);
                    assertThat(divergence.message()).isEqualTo("Alíquota de CBS 5% diferente da referência de 2027 (8.8%)");
                });
    }

    /** CT-AL-06: CST 410 is not compared (only 000 and 200, current rule). */
    @Test
    void untaxedCstIsNotCompared() {
        when(rateRepository.findFindingRates()).thenReturn(List.of(law2026(), law2027()));
        FiscalCheckContext context = engine.contextFor(companyId);

        FiscalDocumentItem immune = item(LocalDateTime.of(2027, 3, 1, 9, 0), "410", "5", "0.1", "0");
        assertThat(itemCheck.check(immune, context)).extracting(FiscalDivergence::code).doesNotContain(IbsCbsItemCheck.RATE);
        verify(ruleRepository, times(1)).findByCompany_IdAndDeletedIsFalse(any());
    }

    private static FiscalDocumentItem item(LocalDateTime issuedAt, String cst, String pCbs, String pIbsUf, String pIbsMun) {
        GoodsInvoice document = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).issuedAt(issuedAt).taxRegime("3").build();
        BigDecimal base = new BigDecimal("100");
        BigDecimal ibsUf = base.multiply(new BigDecimal(pIbsUf)).movePointLeft(2);
        BigDecimal ibsMun = base.multiply(new BigDecimal(pIbsMun)).movePointLeft(2);
        BigDecimal cbs = base.multiply(new BigDecimal(pCbs)).movePointLeft(2);
        ItemIbsCbs group = new ItemIbsCbs(cst, cst + "001", base, new BigDecimal(pIbsUf), null, ibsUf,
                new BigDecimal(pIbsMun), null, ibsMun, ibsUf.add(ibsMun), new BigDecimal(pCbs), null, cbs, false);
        FiscalDocumentItem item = TestNotes.item(1).ibsCbs(group).build();
        document.addItem(item);
        return item;
    }
}
