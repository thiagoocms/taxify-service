package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.domain.taxclearance.StateTaxClearance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TaxClearanceRenewalRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static StateTaxClearance validUntil(LocalDate date) {
        StateTaxClearance clearance = new StateTaxClearance();
        clearance.setValidUntil(date);
        return clearance;
    }

    @Test
    void issuesWhenThereIsNone() {
        assertThat(TaxClearanceRenewalRule.due(null, TODAY)).isTrue();
        assertThat(TaxClearanceRenewalRule.status(null, TODAY)).isEqualTo(TaxClearanceRenewalRule.Status.SEM_CERTIDAO);
        assertThat(TaxClearanceRenewalRule.due(validUntil(null), TODAY)).isTrue();
    }

    @Test
    void issuesWhen15DaysOrLessAreLeft() {
        assertThat(TaxClearanceRenewalRule.due(validUntil(TODAY.plusDays(16)), TODAY)).isFalse();
        assertThat(TaxClearanceRenewalRule.status(validUntil(TODAY.plusDays(16)), TODAY)).isEqualTo(TaxClearanceRenewalRule.Status.VALIDA);

        assertThat(TaxClearanceRenewalRule.due(validUntil(TODAY.plusDays(15)), TODAY)).isTrue();
        assertThat(TaxClearanceRenewalRule.status(validUntil(TODAY.plusDays(15)), TODAY)).isEqualTo(TaxClearanceRenewalRule.Status.A_VENCER);
        assertThat(TaxClearanceRenewalRule.status(validUntil(TODAY), TODAY)).isEqualTo(TaxClearanceRenewalRule.Status.A_VENCER);
    }

    @Test
    void issuesWhenExpired() {
        assertThat(TaxClearanceRenewalRule.due(validUntil(TODAY.minusDays(1)), TODAY)).isTrue();
        assertThat(TaxClearanceRenewalRule.status(validUntil(TODAY.minusDays(1)), TODAY)).isEqualTo(TaxClearanceRenewalRule.Status.VENCIDA);
        assertThat(TaxClearanceRenewalRule.daysLeft(validUntil(TODAY.minusDays(1)), TODAY)).isEqualTo(-1);
    }
}
