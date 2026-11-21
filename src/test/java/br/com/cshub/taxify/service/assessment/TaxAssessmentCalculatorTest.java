package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.service.CompanyTaxRegimeService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.assessment.AssessmentAlertDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.dto.assessment.TaxEstimateDTO;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.service.assessment.RevenueCfops.Kind;
import br.com.cshub.taxify.service.assessment.TaxAssessmentCalculator.MonthRevenue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaxAssessmentCalculatorTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private static Company company(TaxRegime regime) {
        Company company = new Company(UUID.randomUUID());
        company.setName("Acme");
        company.setTaxRegimeType(regime);
        return company;
    }

    private static MonthRevenue resale(String value) {
        return MonthRevenue.EMPTY.plus(Kind.RESALE, new BigDecimal(value));
    }

    private static MonthRevenue services(String value) {
        return MonthRevenue.EMPTY.plus(Kind.SERVICE, new BigDecimal(value));
    }

    /** Same revenue in each of the 12 months before October plus October itself. */
    private static Map<YearMonth, MonthRevenue> steady(MonthRevenue monthly) {
        Map<YearMonth, MonthRevenue> map = new HashMap<>();
        for (YearMonth m = OCT.minusMonths(12); !m.isAfter(OCT); m = m.plusMonths(1)) {
            map.put(m, monthly);
        }
        return map;
    }

    private static TaxAssessmentDTO assess(Company company, Map<YearMonth, MonthRevenue> revenue, long invoices, YearMonth firstSale) {
        return TaxAssessmentCalculator.assess(new TaxAssessmentCalculator.Input(company, OCT,
                new CompanyTaxRegimeService.RegimeAt(company.getTaxRegimeType(), company.getBusinessActivity(), company.getSimplesServiceAnnex(), null),
                revenue, invoices, firstSale, true));
    }

    private static TaxEstimateDTO tax(TaxAssessmentDTO result, String code) {
        return result.taxes().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void cfopsAreClassifiedByDirectionAndOperation() {
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "5102")).isEqualTo(Kind.RESALE);
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "6101")).isEqualTo(Kind.OWN_PRODUCTION_SALE);
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "5933")).isEqualTo(Kind.SERVICE);
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "5405")).isEqualTo(Kind.RESALE);
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "5152")).isEqualTo(Kind.NONE);   // transferência
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "5922")).isEqualTo(Kind.NONE);   // simples faturamento
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "5910")).isEqualTo(Kind.NONE);   // bonificação
        assertThat(RevenueCfops.classify(FiscalDirection.INBOUND, "1202")).isEqualTo(Kind.SALES_RETURN);
        assertThat(RevenueCfops.classify(FiscalDirection.INBOUND, "6202")).isEqualTo(Kind.SALES_RETURN); // customer's note
        assertThat(RevenueCfops.classify(FiscalDirection.INBOUND, "5102")).isEqualTo(Kind.PURCHASE);     // supplier's note
        assertThat(RevenueCfops.classify(FiscalDirection.INBOUND, "2102")).isEqualTo(Kind.PURCHASE);
        assertThat(RevenueCfops.classify(FiscalDirection.INBOUND, "1556")).isEqualTo(Kind.NONE);
        assertThat(RevenueCfops.classify(FiscalDirection.OUTBOUND, "51O2")).isEqualTo(Kind.NONE);
    }

    @Test
    void simplesUsesTheEffectiveRateOfEachAnnex() {
        Company company = company(TaxRegime.SIMPLES_NACIONAL);
        company.setSimplesServiceAnnex(SimplesAnnex.III);
        // RBT12 = 12 × (30k + 10k) = 480k → 3rd bracket
        TaxAssessmentDTO result = assess(company, steady(resale("30000").add(services("10000"))), 10, YearMonth.of(2020, 1));

        assertThat(result.rbt12()).isEqualByComparingTo("480000");
        assertThat(result.rbt12Proportional()).isFalse();
        // anexo I: (480000 × 9,5% − 13860) / 480000 = 6,6125%
        assertThat(tax(result, "DAS_I").rate()).isEqualByComparingTo("6.6125");
        assertThat(tax(result, "DAS_I").amount()).isEqualByComparingTo("1983.75");
        // anexo III: (480000 × 13,5% − 17640) / 480000 = 9,825%
        assertThat(tax(result, "DAS_III").rate()).isEqualByComparingTo("9.8250");
        assertThat(tax(result, "DAS_III").amount()).isEqualByComparingTo("982.50");
        assertThat(result.totalTaxes()).isEqualByComparingTo("2966.25");
        assertThat(result.history()).hasSize(13);
        assertThat(result.alerts()).isEmpty();
    }

    @Test
    void salesReturnsComeOffTheRevenue() {
        MonthRevenue october = resale("10000").add(MonthRevenue.EMPTY.plus(Kind.SALES_RETURN, new BigDecimal("2500")));
        Map<YearMonth, MonthRevenue> revenue = new HashMap<>(Map.of(OCT, october));

        TaxAssessmentDTO result = assess(company(TaxRegime.SIMPLES_NACIONAL), revenue, 3, OCT);

        assertThat(result.revenue().net()).isEqualByComparingTo("7500");
        assertThat(tax(result, "DAS_I").base()).isEqualByComparingTo("7500");
    }

    @Test
    void firstMonthsProportionalizeRbt12() {
        Map<YearMonth, MonthRevenue> revenue = new HashMap<>();
        revenue.put(OCT.minusMonths(2), resale("10000"));
        revenue.put(OCT.minusMonths(1), resale("20000"));
        revenue.put(OCT, resale("5000"));

        TaxAssessmentDTO result = assess(company(TaxRegime.SIMPLES_NACIONAL), revenue, 1, OCT.minusMonths(2));

        // average of the 2 months before × 12
        assertThat(result.rbt12()).isEqualByComparingTo("180000");
        assertThat(result.rbt12Proportional()).isTrue();
        assertThat(result.alerts()).extracting(AssessmentAlertDTO::code).contains("RBT12_PROPORTIONAL");

        TaxAssessmentDTO firstMonth = assess(company(TaxRegime.SIMPLES_NACIONAL), Map.of(OCT, resale("5000")), 1, OCT);
        assertThat(firstMonth.rbt12()).isEqualByComparingTo("60000");
    }

    @Test
    void simplesAboveTheSublimitIsFlagged() {
        TaxAssessmentDTO result = assess(company(TaxRegime.SIMPLES_NACIONAL), steady(resale("320000")), 50, YearMonth.of(2020, 1));

        assertThat(result.rbt12()).isEqualByComparingTo("3840000");
        assertThat(result.alerts()).extracting(AssessmentAlertDTO::code).containsExactly("SIMPLES_SUBLIMIT");
    }

    @Test
    void meiAboveTheLimitPlusTwentyPercentIsExcluded() {
        TaxAssessmentDTO result = assess(company(TaxRegime.MEI), steady(services("10000")), 4, YearMonth.of(2020, 1));

        assertThat(result.yearToDate()).isEqualByComparingTo("100000");
        assertThat(result.alerts()).extracting(AssessmentAlertDTO::code).containsExactly("MEI_EXCLUSION");
        assertThat(tax(result, "DAS_MEI").amount()).isNull();
    }

    @Test
    void presumidoEstimatesPisCofinsAndPresumedProfit() {
        TaxAssessmentDTO result = assess(company(TaxRegime.LUCRO_PRESUMIDO), Map.of(OCT, resale("100000").add(services("50000"))),
                20, YearMonth.of(2020, 1));

        assertThat(tax(result, "PIS").amount()).isEqualByComparingTo("975.00");
        assertThat(tax(result, "COFINS").amount()).isEqualByComparingTo("4500.00");
        // base IRPJ = 8% × 100k + 32% × 50k = 24k → 15% = 3600 + 10% × (24k − 20k) = 400
        assertThat(tax(result, "IRPJ").base()).isEqualByComparingTo("24000");
        assertThat(tax(result, "IRPJ").amount()).isEqualByComparingTo("4000.00");
        // base CSLL = 12% × 100k + 32% × 50k = 28k → 9% = 2520
        assertThat(tax(result, "CSLL").amount()).isEqualByComparingTo("2520.00");
    }

    @Test
    void realDeductsCreditsOnPurchases() {
        MonthRevenue october = resale("100000").add(MonthRevenue.EMPTY.plus(Kind.PURCHASE, new BigDecimal("60000")));

        TaxAssessmentDTO result = assess(company(TaxRegime.LUCRO_REAL), Map.of(OCT, october), 20, YearMonth.of(2020, 1));

        assertThat(tax(result, "PIS").amount()).isEqualByComparingTo("660.00");
        assertThat(tax(result, "COFINS").amount()).isEqualByComparingTo("3040.00");
        assertThat(tax(result, "IRPJ_CSLL").amount()).isNull();
    }

    @Test
    void missingRegimeAndNoInvoicesAreFlagged() {
        TaxAssessmentDTO result = assess(company(null), Map.of(), 0, null);

        assertThat(result.taxes()).isEmpty();
        assertThat(result.alerts()).extracting(AssessmentAlertDTO::code).containsExactly("REGIME_MISSING", "NO_INVOICES");
    }
}
