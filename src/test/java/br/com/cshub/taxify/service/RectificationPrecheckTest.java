package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.support.TestNotes;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RectificationPrecheckTest {

    private static FiscalDocumentItem item(String cfop, String ncm, String cstIcms, String csosn) {
        return TestNotes.item(1).code("P001").cfop(cfop).ncm(ncm)
                .icms(csosn != null ? csosn : cstIcms, null, null, null).icmsDetail("0", csosn).build();
    }

    private static ProductFiscalRule rule(String cfop, String ncm, ProductTaxRule tax) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop(cfop);
        rule.setCorrectNcm(ncm);
        rule.setTaxRule(tax);
        return rule;
    }

    private static ProductTaxRule tax(String origem, String cstIcms, String csosn) {
        return new ProductTaxRule(origem, cstIcms, csosn, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void matchingItemNeedsNoRead() {
        assertThat(RectificationPrecheck.mayChange(item("5102", "12345678", "00", null), rule("5102", "12345678", tax("0", "00", null))))
                .isFalse();
    }

    @Test
    void differentCfopOrPinnedNcmNeedsRead() {
        assertThat(RectificationPrecheck.mayChange(item("5405", "12345678", "00", null), rule("5102", null, null))).isTrue();
        assertThat(RectificationPrecheck.mayChange(item("5102", "12345678", "00", null), rule("5102", "87654321", null))).isTrue();
    }

    @Test
    void ncmNotPinnedByTheRuleIsIgnored() {
        assertThat(RectificationPrecheck.mayChange(item("5102", "12345678", "00", null), rule("5102", null, null))).isFalse();
    }

    @Test
    void comparesCstOrCsosnByWhatTheNoteHas() {
        // Simples note (CSOSN): the rule's CST doesn't apply, its CSOSN does.
        assertThat(RectificationPrecheck.mayChange(item("5102", null, null, "102"), rule("5102", null, tax(null, "00", null)))).isFalse();
        assertThat(RectificationPrecheck.mayChange(item("5102", null, null, "102"), rule("5102", null, tax(null, null, "103")))).isTrue();
        // Regime normal note (CST).
        assertThat(RectificationPrecheck.mayChange(item("5102", null, "40", null), rule("5102", null, tax(null, "41", "102")))).isTrue();
    }

    @Test
    void fieldTheNoteDoesNotHaveIsNeverRewritten() {
        FiscalDocumentItem item = item("5102", null, "00", null);
        ProductTaxRule tax = new ProductTaxRule(null, null, null, null, null, "50", null, "01", null, "01", null, null, "000001");

        assertThat(RectificationPrecheck.mayChange(item, rule("5102", null, tax))).isFalse();
    }
}
