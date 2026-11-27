package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class Phase2ChecksTest {

    private final FiscalCheckContext noRules = new FiscalCheckContext(UUID.randomUUID(), Map.of());

    // ------------------------------------------------------------------ ProductRuleCheck

    @Test
    void ruleComparesTheTaxFieldsItSets() {
        ProductFiscalRule rule = rule(new ProductTaxRule("0", "00", "102", new BigDecimal("18"), "0300700",
                "50", new BigDecimal("5"), "01", new BigDecimal("1.65"), "01", new BigDecimal("7.6"), "000", "000001"));
        FiscalXmlDocumentItem item = item(document("3", "1", "SP", "SP"), "00", "100", "12", "12.00",
                detail("1", null, "0300799", null, null, null, "100", "5", "100", "1.65", "100", "3"));
        item.setSituacaoTributariaIpi("50");
        item.setSituacaoTributariaPis("01");
        item.setSituacaoTributariaCofins("02");

        List<FiscalDivergence> found = new ProductRuleCheck().check(item, contextWith(rule));

        assertThat(found).extracting(FiscalDivergence::code, FiscalDivergence::found, FiscalDivergence::expected)
                .containsExactlyInAnyOrder(
                        tuple(ProductRuleCheck.ORIGEM, "1", "0"),
                        tuple(ProductRuleCheck.CEST, "0300799", "0300700"),
                        tuple(ProductRuleCheck.ALIQUOTA_ICMS, "12", "18"),
                        tuple(ProductRuleCheck.ALIQUOTA_COFINS, "3", "7.6"),
                        tuple(ProductRuleCheck.CST_COFINS, "02", "01"));
    }

    @Test
    void ruleComparesCsosnOnlyWithCsosnNotesAndInternalRateOnlyInInternalOperations() {
        ProductFiscalRule rule = rule(new ProductTaxRule(null, "00", "101", new BigDecimal("18"), null,
                null, null, null, null, null, null, null, null));
        FiscalXmlDocumentItem simples = item(document("1", "1", "SP", "SP"), "102", null, null, null,
                detail("0", "102", null, null, null, null, null, null, null, null, null, null));
        FiscalXmlDocumentItem interstate = item(document("3", "2", "SP", "BA"), "00", "100", "7", "7.00",
                detail("0", null, null, null, null, null, null, null, null, null, null, null));
        interstate.setCfop("6102"); // the rule's 5102 on an interstate operation

        assertThat(new ProductRuleCheck().check(simples, contextWith(rule)))
                .extracting(FiscalDivergence::code).containsExactly(ProductRuleCheck.CSOSN);
        assertThat(new ProductRuleCheck().check(interstate, contextWith(rule))).isEmpty();
    }

    // ------------------------------------------------------------------ TaxRegimeCodeCheck

    @Test
    void codeMustFitTheIssuerRegime() {
        TaxRegimeCodeCheck check = new TaxRegimeCodeCheck();
        FiscalXmlDocumentItem simplesWithCst = item(document("1", "1", "SP", "SP"), "00", null, null, null,
                detail("0", null, null, null, null, null, null, null, null, null, null, null));
        FiscalXmlDocumentItem normalWithCsosn = item(document("3", "1", "SP", "SP"), "102", null, null, null,
                detail("0", "102", null, null, null, null, null, null, null, null, null, null));
        FiscalXmlDocumentItem fine = item(document("2", "1", "SP", "SP"), "00", null, null, null,
                detail("0", null, null, null, null, null, null, null, null, null, null, null));

        assertThat(check.check(simplesWithCst, noRules)).extracting(FiscalDivergence::field).containsExactly("CST_ICMS");
        assertThat(check.check(normalWithCsosn, noRules)).extracting(FiscalDivergence::field).containsExactly("CSOSN");
        assertThat(check.check(fine, noRules)).isEmpty();
    }

    // ------------------------------------------------------------------ TaxCalculationCheck

    @Test
    void flagsMiscalculatedTaxesAndTaxOnAnExemptCode() {
        FiscalXmlDocumentItem item = item(document("3", "1", "SP", "SP"), "10", "100", "18", "17.00",
                detail("0", null, null, "140", "18", "7.20", "100", "5", "100", "1.65", "100", "7.6"));
        item.setValorIpi(new BigDecimal("5.00"));
        item.setValorPis(new BigDecimal("1.65"));
        item.setValorCofins(new BigDecimal("7.60"));
        item.setSituacaoTributariaPis("06");

        List<FiscalDivergence> found = new TaxCalculationCheck().check(item, noRules);

        assertThat(found).extracting(FiscalDivergence::code, FiscalDivergence::field, FiscalDivergence::severity)
                .containsExactlyInAnyOrder(
                        tuple(TaxCalculationCheck.ZERO_TAX, "vPIS", DivergenceSeverity.ERROR),
                        tuple(TaxCalculationCheck.CALCULATION, "vICMS", DivergenceSeverity.ERROR),
                        // ST esperado: 140 x 18% - 17,00 = 8,20
                        tuple(TaxCalculationCheck.CALCULATION, "vICMSST", DivergenceSeverity.WARNING));
    }

    @Test
    void correctItemAndSimplesItemHaveNoCalculationFinding() {
        FiscalXmlDocumentItem correct = item(document("3", "1", "SP", "SP"), "10", "100", "18", "18.00",
                detail("0", null, null, "140", "18", "7.20", "100", "5", null, null, null, null));
        correct.setValorIpi(new BigDecimal("5.00"));
        FiscalXmlDocumentItem simples = item(document("1", "1", "SP", "SP"), "102", null, null, null,
                detail("0", "102", null, null, null, null, null, null, null, null, null, null));

        assertThat(new TaxCalculationCheck().check(correct, noRules)).isEmpty();
        assertThat(new TaxCalculationCheck().check(simples, noRules)).isEmpty();
    }

    // ------------------------------------------------------------------ OperationCheck

    @Test
    void interstateRateFollowsOriginAndStates() {
        assertThat(OperationCheck.expectedInterstateRate("1", "SP", "BA")).isEqualByComparingTo("4");
        assertThat(OperationCheck.expectedInterstateRate("0", "SP", "BA")).isEqualByComparingTo("7");
        assertThat(OperationCheck.expectedInterstateRate("0", "SP", "ES")).isEqualByComparingTo("7");
        assertThat(OperationCheck.expectedInterstateRate("0", "SP", "RJ")).isEqualByComparingTo("12");
        assertThat(OperationCheck.expectedInterstateRate("0", "BA", "SP")).isEqualByComparingTo("12");
        assertThat(OperationCheck.expectedInterstateRate("0", "ES", "BA")).isEqualByComparingTo("12");
        assertThat(OperationCheck.expectedInterstateRate("6", "SP", "BA")).isEqualByComparingTo("7");
        assertThat(OperationCheck.expectedInterstateRate("0", "SP", "SP")).isNull();
    }

    @Test
    void flagsWrongInterstateRateAndCfopOfAnotherDestination() {
        FiscalXmlDocumentItem item = item(document("3", "2", "SP", "BA"), "00", "100", "12", "12.00",
                detail("0", null, null, null, null, null, null, null, null, null, null, null));
        item.setCfop("5102");

        assertThat(new OperationCheck().check(item, noRules))
                .extracting(FiscalDivergence::code, FiscalDivergence::expected)
                .containsExactlyInAnyOrder(
                        tuple(OperationCheck.CFOP_DESTINATION, "2xxx/6xxx"),
                        tuple(OperationCheck.INTERSTATE_RATE, "7"));
    }

    @Test
    void importedGoodsAtFourPercentIsFine() {
        FiscalXmlDocumentItem item = item(document("3", "2", "SP", "BA"), "00", "100", "4", "4.00",
                detail("1", null, null, null, null, null, null, null, null, null, null, null));
        item.setCfop("6102");

        assertThat(new OperationCheck().check(item, noRules)).isEmpty();
    }

    // ------------------------------------------------------------------ IcmsTotalsCheck

    @Test
    void totalsMustMatchTheItems() {
        FiscalXmlDocument document = document("3", "1", "SP", "SP");
        FiscalXmlDocumentItem a = item(document, "00", "100", "18", "18.00", null);
        FiscalXmlDocumentItem b = item(document, "00", "50", "18", "9.00", null);
        document.setBaseCalculoIcms(new BigDecimal("150"));
        document.setValorIcms(new BigDecimal("27.00"));
        document.setValorIpi(new BigDecimal("3.00"));

        assertThat(new IcmsTotalsCheck().check(document, List.of(a, b), noRules))
                .extracting(FiscalDivergence::field, FiscalDivergence::found, FiscalDivergence::expected)
                .containsExactly(tuple("vIPI", "3", "0"));
    }

    // ------------------------------------------------------------------ helpers

    private FiscalCheckContext contextWith(ProductFiscalRule rule) {
        return new FiscalCheckContext(UUID.randomUUID(), Map.of(rule.getProductCode(), rule));
    }

    private static ProductFiscalRule rule(ProductTaxRule tax) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P1");
        rule.setCorrectCfop("5102");
        rule.setTaxRule(tax);
        return rule;
    }

    private static FiscalXmlDocument document(String crt, String idDest, String fromUf, String toUf) {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        document.setDocumentType("NFE");
        document.setIssueDate(LocalDateTime.of(2026, 5, 1, 10, 0));
        document.setEmitenteRegimeTributario(crt);
        document.setDestinoOperacao(idDest);
        document.setEmitenteUf(fromUf);
        document.setDestinatarioUf(toUf);
        return document;
    }

    private static FiscalXmlDocumentItem item(FiscalXmlDocument document, String cstIcms, String base, String rate,
                                              String value, FiscalItemTaxDetail detail) {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setDocument(document);
        item.setCodigo("P1");
        item.setCfop("5102");
        item.setSituacaoTributariaIcms(cstIcms);
        item.setBaseCalculoIcms(base == null ? null : new BigDecimal(base));
        item.setAliquotaIcms(rate == null ? null : new BigDecimal(rate));
        item.setValorIcms(value == null ? null : new BigDecimal(value));
        item.setTaxDetail(detail);
        return item;
    }

    private static FiscalItemTaxDetail detail(String origem, String csosn, String cest, String baseSt, String rateSt,
                                              String valueSt, String baseIpi, String rateIpi, String basePis, String ratePis,
                                              String baseCofins, String rateCofins) {
        return new FiscalItemTaxDetail(cest, null, origem, csosn, null, null, null, null, null,
                dec(baseSt), dec(rateSt), dec(valueSt), dec(baseIpi), dec(rateIpi), dec(basePis), dec(ratePis),
                dec(baseCofins), dec(rateCofins));
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
