package br.com.cshub.taxify.service.certidao;

import br.com.cshub.taxify.domain.certidao.CertidaoEstadual;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CertidaoRenewalRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static CertidaoEstadual validUntil(LocalDate date) {
        CertidaoEstadual certidao = new CertidaoEstadual();
        certidao.setValidaAte(date);
        return certidao;
    }

    @Test
    void issuesWhenThereIsNone() {
        assertThat(CertidaoRenewalRule.due(null, TODAY)).isTrue();
        assertThat(CertidaoRenewalRule.status(null, TODAY)).isEqualTo(CertidaoRenewalRule.Status.SEM_CERTIDAO);
        assertThat(CertidaoRenewalRule.due(validUntil(null), TODAY)).isTrue();
    }

    @Test
    void issuesWhen15DaysOrLessAreLeft() {
        assertThat(CertidaoRenewalRule.due(validUntil(TODAY.plusDays(16)), TODAY)).isFalse();
        assertThat(CertidaoRenewalRule.status(validUntil(TODAY.plusDays(16)), TODAY)).isEqualTo(CertidaoRenewalRule.Status.VALIDA);

        assertThat(CertidaoRenewalRule.due(validUntil(TODAY.plusDays(15)), TODAY)).isTrue();
        assertThat(CertidaoRenewalRule.status(validUntil(TODAY.plusDays(15)), TODAY)).isEqualTo(CertidaoRenewalRule.Status.A_VENCER);
        assertThat(CertidaoRenewalRule.status(validUntil(TODAY), TODAY)).isEqualTo(CertidaoRenewalRule.Status.A_VENCER);
    }

    @Test
    void issuesWhenExpired() {
        assertThat(CertidaoRenewalRule.due(validUntil(TODAY.minusDays(1)), TODAY)).isTrue();
        assertThat(CertidaoRenewalRule.status(validUntil(TODAY.minusDays(1)), TODAY)).isEqualTo(CertidaoRenewalRule.Status.VENCIDA);
        assertThat(CertidaoRenewalRule.daysLeft(validUntil(TODAY.minusDays(1)), TODAY)).isEqualTo(-1);
    }
}
