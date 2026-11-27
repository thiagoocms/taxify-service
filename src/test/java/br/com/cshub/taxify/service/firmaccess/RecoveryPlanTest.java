package br.com.cshub.taxify.service.firmaccess;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/** QA-02 (decisão do Tony): o que recuperar na reativação, só fora da janela normal e até N meses atrás. */
class RecoveryPlanTest {

    /** CT1-22: bloqueio em 25/12, reativação em 04/01 → SIEG NF-e de dezembro; NFS-e e e-Fisco já estão na janela normal. */
    @Test
    void blockedOnDecember25ReactivatedOnJanuary4RecoversDecember() {
        RecoveryPlan.Plan plan = RecoveryPlan.plan(LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 4), 3);

        assertThat(plan.nfeMonths()).containsExactly(YearMonth.of(2026, 12));
        assertThat(plan.nfseWindows()).isEmpty();
        assertThat(plan.efiscoMonths()).isEmpty();
        assertThat(plan.notRecoveredMonths()).isEmpty();
    }

    /** Bloqueio de 5 meses (ago a jan) com o limite de 3: só out, nov e dez; e-Fisco até nov; NFS-e antes dos 35 dias. */
    @Test
    void aFiveMonthBlockRecoversOnlyTheThreeMostRecentMonths() {
        RecoveryPlan.Plan plan = RecoveryPlan.plan(LocalDate.of(2026, 8, 1), LocalDate.of(2027, 1, 4), 3);

        assertThat(plan.nfeMonths()).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12));
        assertThat(plan.efiscoMonths()).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11));
        // CA1-28 / CT1-46: the months beyond the limit are listed (for the trail), not fetched
        assertThat(plan.notRecoveredMonths()).containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9));
        assertThat(plan.nfseWindows()).containsExactly(
                new RecoveryPlan.Window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
                new RecoveryPlan.Window(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 29)));
    }

    /** A NFS-e começa no dia do bloqueio, não no dia 1. */
    @Test
    void theServiceInvoicesStartOnTheBlockDay() {
        RecoveryPlan.Plan plan = RecoveryPlan.plan(LocalDate.of(2026, 10, 20), LocalDate.of(2027, 1, 4), 3);

        assertThat(plan.nfseWindows().getFirst()).isEqualTo(new RecoveryPlan.Window(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 31)));
        assertThat(plan.efiscoMonths()).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11));
    }

    /** Bloqueio e reativação no mesmo mês: tudo está na janela normal, nada a recuperar. */
    @Test
    void aBlockInsideTheCurrentMonthNeedsNothing() {
        assertThat(RecoveryPlan.plan(LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 20), 3).isEmpty()).isTrue();
        assertThat(RecoveryPlan.plan(null, LocalDate.of(2027, 1, 20), 3).isEmpty()).isTrue();
        assertThat(RecoveryPlan.plan(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 20), 0).isEmpty()).isTrue();
    }

    /** O limite é configurável (TAXIFY_UNBLOCK_RECOVERY_MONTHS). */
    @Test
    void theLimitIsConfigurable() {
        assertThat(RecoveryPlan.plan(LocalDate.of(2026, 8, 1), LocalDate.of(2027, 1, 4), 1).nfeMonths())
                .containsExactly(YearMonth.of(2026, 12));
    }
}
