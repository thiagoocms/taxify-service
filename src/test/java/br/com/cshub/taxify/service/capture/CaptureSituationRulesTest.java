package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.enumerated.CaptureChannel;
import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureSituation;
import br.com.cshub.taxify.enumerated.CaptureStage;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.DirectFacts;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.SiegFacts;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.StageFacts;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.Verdict;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CP-RN-06 a CP-RN-08 linha a linha, com o cenário base da spec: hoje é 2026-11-27 10:00 em Recife (CT-CP-01 a 09, 20 a
 * 30, 66, 67). Puro, sem banco.
 */
class CaptureSituationRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final CaptureSituationRules.Clock CLOCK = new CaptureSituationRules.Clock(NOW, TODAY, 24, 3);

    // ------------------------------------------------------------------ builders

    private static SiegFacts sieg(int receivedTotal, int receivedImported, LocalDateTime receivedAt, Integer issuedTotal,
                                  Integer issuedImported, LocalDateTime issuedAt) {
        return siegWith(receivedTotal, receivedImported, receivedAt, issuedTotal, issuedImported, issuedAt, TODAY, TODAY, TODAY,
                Map.of(), null, null, null);
    }

    private static SiegFacts siegWith(int receivedTotal, int receivedImported, LocalDateTime receivedAt, Integer issuedTotal,
                                      Integer issuedImported, LocalDateTime issuedAt, LocalDate eventsReceived,
                                      LocalDate eventsIssued, LocalDate nfse, Map<CaptureStage, StageFacts> stages,
                                      String prevMonth, Integer prevTotal, Integer prevImported) {
        return new SiegFacts(true, true, true, NOW.minusMinutes(30), receivedTotal, receivedImported, receivedAt, issuedTotal,
                issuedImported, issuedAt, eventsReceived, eventsIssued, nfse, prevMonth, prevTotal, prevImported, null, null, null,
                stages, 3);
    }

    private static StageFacts failure(CaptureStage stage, CaptureFailureCode code, String status, LocalDateTime at, int count) {
        return new StageFacts(stage, true, at, null, at, code, status, null, null, count);
    }

    private static DirectFacts sefaz(boolean certificate, LocalDateTime lastRunAt, String lastStatus, StageFacts stage) {
        return new DirectFacts(CaptureChannel.SEFAZ, true, true, true, false, true, false, certificate,
                certificate ? LocalDate.of(2027, 3, 15) : null, lastRunAt != null, lastRunAt, lastStatus, stage);
    }

    // ------------------------------------------------------------------ SIEG

    /** CT-CP-01 / CA-CP-03. */
    @Test
    void bothSidesCompleteAndSweptTodayIsUpToDate() {
        Verdict verdict = CaptureSituationRules.sieg(sieg(40, 40, NOW.withHour(8).withMinute(10), 12, 12,
                NOW.withHour(8).withMinute(10)), CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.EM_DIA);
    }

    /** CT-CP-02 / CA-CP-04: 200 − 180 = 20. */
    @Test
    void receivedSideShortOfTheSiegTotalIsPending() {
        Verdict verdict = CaptureSituationRules.sieg(sieg(200, 180, NOW.withHour(9), 12, 12, NOW.withHour(9)), CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.PENDENTE);
        assertThat(verdict.reason().key()).isEqualTo("capture.reason.missing.received");
        assertThat(verdict.reason().args()).containsExactly(20);
    }

    /** CT-CP-03: notes from SEFAZ/upload the SIEG does not have are not a pendency. */
    @Test
    void moreImportedThanSiegIsUpToDate() {
        assertThat(CaptureSituationRules.sieg(sieg(30, 35, NOW.withHour(9), 0, 0, NOW.withHour(9)), CLOCK).situation())
                .isEqualTo(CaptureSituation.EM_DIA);
    }

    /** CT-CP-04. */
    @Test
    void monthWithoutNotesIsUpToDate() {
        assertThat(CaptureSituationRules.sieg(sieg(0, 0, NOW.withHour(9).withMinute(30), 0, 0, NOW.withHour(9).withMinute(30)),
                CLOCK).situation()).isEqualTo(CaptureSituation.EM_DIA);
    }

    /** CT-CP-05 / CA-CP-05: 23 h 30 is inside the 24 h window; 24 h 30 and 26 h are late. */
    @Test
    void theTwentyFourHourWindow() {
        LocalDateTime inside = LocalDateTime.of(2026, 11, 26, 10, 30);
        assertThat(CaptureSituationRules.sieg(sieg(1, 1, inside, 1, 1, inside), CLOCK).situation()).isEqualTo(CaptureSituation.EM_DIA);

        LocalDateTime outside = LocalDateTime.of(2026, 11, 26, 9, 30);
        Verdict late = CaptureSituationRules.sieg(sieg(1, 1, outside, 1, 1, outside), CLOCK);
        assertThat(late.situation()).isEqualTo(CaptureSituation.ATRASADA);
        assertThat(late.reason().key()).isEqualTo("capture.reason.verification.late");
        assertThat(late.reason().args()).containsExactly(24L);

        LocalDateTime z = LocalDateTime.of(2026, 11, 26, 8, 0);
        assertThat(CaptureSituationRules.sieg(sieg(1, 1, z, 1, 1, z), CLOCK).reason().args()).containsExactly(26L);
    }

    /** CT-CP-06: issued side never checked = never fully verified. */
    @Test
    void neverFullyVerifiedIsLate() {
        Verdict verdict = CaptureSituationRules.sieg(sieg(1, 1, NOW.withHour(9), null, null, null), CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.ATRASADA);
        assertThat(verdict.reason().key()).isEqualTo("capture.reason.never.verified");
    }

    /** CT-CP-07: events swept on 25/11 (before yesterday). */
    @Test
    void eventsNotSweptSinceBeforeYesterdayAreLate() {
        Verdict verdict = CaptureSituationRules.sieg(siegWith(1, 1, NOW.withHour(9), 1, 1, NOW.withHour(9), TODAY,
                LocalDate.of(2026, 11, 25), TODAY, Map.of(), null, null, null), CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.ATRASADA);
        assertThat(verdict.reason().key()).isEqualTo("capture.reason.sweep.late");
        assertThat(verdict.reason().args()).containsExactly(LocalDate.of(2026, 11, 25));
        // yesterday is fine
        assertThat(CaptureSituationRules.sieg(siegWith(1, 1, NOW.withHour(9), 1, 1, NOW.withHour(9), TODAY, TODAY.minusDays(1),
                TODAY.minusDays(1), Map.of(), null, null, null), CLOCK).situation()).isEqualTo(CaptureSituation.EM_DIA);
    }

    /** CT-CP-08: on 01/12 02:00, November 180/200 checked 30/11 23:00 - in the window, last month's counters: not pending. */
    @Test
    void turnOfTheMonthWithLastMonthCountersIsNotPending() {
        LocalDateTime dec1 = LocalDateTime.of(2026, 12, 1, 2, 0);
        CaptureSituationRules.Clock clock = new CaptureSituationRules.Clock(dec1, dec1.toLocalDate(), 24, 3);
        LocalDateTime checked = LocalDateTime.of(2026, 11, 30, 23, 0);
        SiegFacts facts = siegWith(200, 180, checked, 5, 5, checked, dec1.toLocalDate(), dec1.toLocalDate(), dec1.toLocalDate(),
                Map.of(), null, null, null);
        assertThat(CaptureSituationRules.sieg(facts, clock).situation()).isEqualTo(CaptureSituation.EM_DIA);
    }

    /** CT-CP-09: position 37 in the API = "Posição: 38" in the text. */
    @Test
    void neverAttemptedShowsThePositionPlusOne() {
        SiegFacts facts = new SiegFacts(true, true, true, null, 0, 0, null, null, null, null, null, null, null, null, null,
                null, null, null, null, Map.of(), 37);
        Verdict verdict = CaptureSituationRules.sieg(facts, CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.NUNCA_TENTADA);
        assertThat(verdict.reason().args()).containsExactly(38);
    }

    /** CT-CP-10 / CA-CP-07: an open failure is COM_ERRO; once resolved, the counters decide again. */
    @Test
    void anOpenStageFailureIsWithErrorAndTheResolvedOneIsNot() {
        Map<CaptureStage, StageFacts> stages = new EnumMap<>(CaptureStage.class);
        stages.put(CaptureStage.SIEG_RECEIVED, failure(CaptureStage.SIEG_RECEIVED, CaptureFailureCode.SIEG_UNAVAILABLE, "503",
                NOW.withHour(9).withMinute(40), 1));
        SiegFacts facts = siegWith(40, 40, NOW.withHour(8), 12, 12, NOW.withHour(8), TODAY, TODAY, TODAY, stages, null, null, null);
        Verdict verdict = CaptureSituationRules.sieg(facts, CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.COM_ERRO);
        assertThat(verdict.failureCode()).isEqualTo(CaptureFailureCode.SIEG_UNAVAILABLE);
        assertThat(verdict.reason().key()).isEqualTo("capture.failure.SIEG_UNAVAILABLE");
        assertThat(verdict.reason().args()).containsExactly("503");

        stages.put(CaptureStage.SIEG_RECEIVED, new StageFacts(CaptureStage.SIEG_RECEIVED, false, NOW, NOW,
                NOW.withHour(9).withMinute(40), CaptureFailureCode.SIEG_UNAVAILABLE, "503", null, null, 0));
        assertThat(CaptureSituationRules.sieg(facts, CLOCK).situation()).isEqualTo(CaptureSituation.EM_DIA);
        StageFacts last = CaptureSituationRules.lastFailure(stages);
        assertThat(last.failure()).as("resolved").isFalse();
        assertThat(last.lastFailureAt()).isEqualTo(NOW.withHour(9).withMinute(40));
    }

    /** CT-CP-66 / CA-CP-26: November closed 180 of 200 = 20 not imported; CT-CP-67: 40/40 closes without pendency. */
    @Test
    void previousMonthClosedShortIsPendingUntilTheNextTurn() {
        LocalDateTime dec1 = LocalDateTime.of(2026, 12, 1, 3, 0);
        CaptureSituationRules.Clock clock = new CaptureSituationRules.Clock(dec1, dec1.toLocalDate(), 24, 3);
        SiegFacts y = siegWith(3, 3, dec1, 1, 1, dec1, dec1.toLocalDate(), dec1.toLocalDate(), dec1.toLocalDate(), Map.of(),
                "2026-11", 200, 180);
        Verdict verdict = CaptureSituationRules.sieg(y, clock);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.PENDENTE);
        assertThat(verdict.reason().key()).isEqualTo("capture.reason.previous.month.missing");
        assertThat(verdict.reason().args()).containsExactly(YearMonth.of(2026, 11), 20);

        SiegFacts x = siegWith(3, 3, dec1, 1, 1, dec1, dec1.toLocalDate(), dec1.toLocalDate(), dec1.toLocalDate(), Map.of(),
                "2026-11", 40, 40);
        assertThat(CaptureSituationRules.sieg(x, clock).situation()).isEqualTo(CaptureSituation.EM_DIA);

        // a month older than the previous one no longer counts (until the next turn only)
        LocalDateTime jan = LocalDateTime.of(2027, 1, 2, 3, 0);
        CaptureSituationRules.Clock janClock = new CaptureSituationRules.Clock(jan, jan.toLocalDate(), 24, 3);
        SiegFacts old = siegWith(3, 3, jan, 1, 1, jan, jan.toLocalDate(), jan.toLocalDate(), jan.toLocalDate(), Map.of(),
                "2026-11", 200, 180);
        assertThat(CaptureSituationRules.sieg(old, janClock).situation()).isEqualTo(CaptureSituation.EM_DIA);
    }

    @Test
    void blockedAndOutOfPlanComeFirst() {
        SiegFacts blocked = new SiegFacts(false, true, true, null, 0, 0, null, null, null, null, null, null, null, null, null,
                null, null, null, null, Map.of(), 0);
        assertThat(CaptureSituationRules.sieg(blocked, CLOCK).situation()).isEqualTo(CaptureSituation.BLOQUEADA);
        SiegFacts noSubscription = new SiegFacts(true, false, false, null, 0, 0, null, null, null, null, null, null, null, null,
                null, null, null, null, null, Map.of(), null);
        Verdict verdict = CaptureSituationRules.sieg(noSubscription, CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.FORA_DO_PLANO);
        assertThat(verdict.reason().key()).isEqualTo("capture.reason.no.subscription");
    }

    // ------------------------------------------------------------------ SEFAZ / ADN

    /** CT-CP-20 / CA-CP-09: SEFAZ in the plan, no A1 of the root. */
    @Test
    void sefazWithoutCertificateIsCertMissing() {
        Verdict verdict = CaptureSituationRules.direct(sefaz(false, null, null, null), CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.COM_ERRO);
        assertThat(verdict.failureCode()).isEqualTo(CaptureFailureCode.CERT_MISSING);
    }

    /** CA-CP-09: expired on 20/11/2026 - the date from company_certificates. */
    @Test
    void expiredCertificateShowsTheDate() {
        DirectFacts facts = new DirectFacts(CaptureChannel.SEFAZ, true, true, true, false, true, false, true,
                LocalDate.of(2026, 11, 20), true, NOW.minusHours(1), "ERRO", null);
        Verdict verdict = CaptureSituationRules.direct(facts, CLOCK);
        assertThat(verdict.failureCode()).isEqualTo(CaptureFailureCode.CERT_EXPIRED);
        assertThat(verdict.reason().args()).containsExactly(LocalDate.of(2026, 11, 20));
    }

    /** CT-CP-22: expires in 23 days - a warning, the situation does not change. */
    @Test
    void certificateCloseToTheEndIsOnlyAWarning() {
        assertThat(CaptureSituationRules.expiresSoon(LocalDate.of(2026, 12, 20), TODAY, 30)).isTrue();
        assertThat(CaptureSituationRules.expiresSoon(LocalDate.of(2027, 3, 15), TODAY, 30)).isFalse();
        DirectFacts facts = new DirectFacts(CaptureChannel.SEFAZ, true, true, true, false, true, false, true,
                LocalDate.of(2026, 12, 20), true, NOW.minusHours(1), "OK", null);
        assertThat(CaptureSituationRules.direct(facts, CLOCK).situation()).isEqualTo(CaptureSituation.EM_DIA);
    }

    /** CT-CP-23: last run 06:15 (3 h 45) is late; 08:15 is up to date. */
    @Test
    void sefazThreeHourWindow() {
        assertThat(CaptureSituationRules.direct(sefaz(true, NOW.withHour(6).withMinute(15), "OK", null), CLOCK).situation())
                .isEqualTo(CaptureSituation.ATRASADA);
        assertThat(CaptureSituationRules.direct(sefaz(true, NOW.withHour(8).withMinute(15), "SEM_MOVIMENTO", null), CLOCK).situation())
                .isEqualTo(CaptureSituation.EM_DIA);
        assertThat(CaptureSituationRules.direct(sefaz(true, null, null, null), CLOCK).situation())
                .isEqualTo(CaptureSituation.NUNCA_TENTADA);
    }

    /** CT-CP-24: 656 is a warning (SUCCESS) - never COM_ERRO. */
    @Test
    void consumoIndevidoIsNotAnError() {
        StageFacts warning = new StageFacts(CaptureStage.SEFAZ_DIST, false, NOW.minusHours(1), NOW.minusHours(1), null, null, null,
                CaptureFailureCode.SEFAZ_CONSUMO_INDEVIDO, null, 0);
        assertThat(CaptureSituationRules.direct(sefaz(true, NOW.minusHours(1), "SEM_MOVIMENTO", warning), CLOCK).situation())
                .isEqualTo(CaptureSituation.EM_DIA);
    }

    /** CT-CP-25: cStat 593 = SEFAZ_REJECTED with the cStat; the text of xMotivo never enters. */
    @Test
    void sefazRejectionShowsTheCstat() {
        StageFacts rejected = failure(CaptureStage.SEFAZ_DIST, CaptureFailureCode.SEFAZ_REJECTED, "593", NOW.minusHours(1), 1);
        Verdict verdict = CaptureSituationRules.direct(sefaz(true, NOW.minusHours(1), "ERRO", rejected), CLOCK);
        assertThat(verdict.failureCode()).isEqualTo(CaptureFailureCode.SEFAZ_REJECTED);
        assertThat(verdict.reason().args()).containsExactly("593");
    }

    /** CT-CP-26 / CA-CP-10: ERRO recorded before the panel, no stage row. */
    @Test
    void errorFromBeforeThePanelIsLegacy() {
        Verdict verdict = CaptureSituationRules.direct(sefaz(true, NOW.minusHours(1), "ERRO", null), CLOCK);
        assertThat(verdict.failureCode()).isEqualTo(CaptureFailureCode.LEGACY_ERROR);
    }

    /** CT-CP-27: UF outside SEFAZ_DIST_UFS has its own reason; no UF is COMPANY_WITHOUT_UF. */
    @Test
    void ufOutsideTheCaptureIsOutOfPlan() {
        DirectFacts pb = new DirectFacts(CaptureChannel.SEFAZ, true, true, true, false, false, false, true, null, false, null,
                null, null);
        Verdict verdict = CaptureSituationRules.direct(pb, CLOCK);
        assertThat(verdict.situation()).isEqualTo(CaptureSituation.FORA_DO_PLANO);
        assertThat(verdict.reason().key()).isEqualTo("capture.reason.sefaz.uf.not.covered");

        DirectFacts noUf = new DirectFacts(CaptureChannel.SEFAZ, true, true, true, true, false, false, true, null, false, null,
                null, null);
        assertThat(CaptureSituationRules.direct(noUf, CLOCK).failureCode()).isEqualTo(CaptureFailureCode.COMPANY_WITHOUT_UF);
    }

    /** QA B-1: the same text everywhere - the certificate's date in CERT_EXPIRED, the status only where it exists. */
    @Test
    void failureReasonCarriesItsParameterOrAVariantWithoutIt() {
        StageFacts expired = failure(CaptureStage.SEFAZ_DIST, CaptureFailureCode.CERT_EXPIRED, null, NOW, 1);
        Verdict fromStage = CaptureSituationRules.direct(new DirectFacts(CaptureChannel.SEFAZ, true, true, true, false, true,
                false, true, LocalDate.of(2027, 1, 1), true, NOW.minusHours(1), "ERRO", expired), CLOCK);
        assertThat(fromStage.reason().args()).containsExactly(LocalDate.of(2027, 1, 1));
        assertThat(CaptureSituationRules.failureReason(expired, LocalDate.of(2026, 11, 20)).args())
                .containsExactly(LocalDate.of(2026, 11, 20));
        assertThat(CaptureSituationRules.failureReason(expired, null).key()).isEqualTo("capture.failure.CERT_EXPIRED.nodate");

        StageFacts auth = failure(CaptureStage.SIEG_RECEIVED, CaptureFailureCode.SIEG_AUTH, null, NOW, 1);
        assertThat(CaptureSituationRules.failureReason(auth, null).key()).isEqualTo("capture.failure.SIEG_AUTH.nostatus");
        StageFacts rejected = failure(CaptureStage.NFSE_ADN, CaptureFailureCode.ADN_REJECTED, "400", NOW, 1);
        assertThat(CaptureSituationRules.failureReason(rejected, null).args()).containsExactly("400");
        StageFacts timeout = failure(CaptureStage.SIEG_RECEIVED, CaptureFailureCode.SIEG_TIMEOUT, null, NOW, 1);
        assertThat(CaptureSituationRules.failureReason(timeout, null).args()).isEmpty();
    }

    // ------------------------------------------------------------------ the company (CP-RN-08)

    /** CT-CP-28: SIEG pending, SEFAZ late, ADN out of the plan = late. */
    @Test
    void theWorstChannelWins() {
        Verdict pending = new Verdict(CaptureSituation.PENDENTE, null, null);
        Verdict late = new Verdict(CaptureSituation.ATRASADA, null, null);
        Verdict out = new Verdict(CaptureSituation.FORA_DO_PLANO, null, null);
        assertThat(CaptureSituationRules.company(true, pending, late, out).situation()).isEqualTo(CaptureSituation.ATRASADA);
        // CA-CP-11: SIEG up to date, SEFAZ with error = with error
        assertThat(CaptureSituationRules.company(true, new Verdict(CaptureSituation.EM_DIA, null, null),
                new Verdict(CaptureSituation.COM_ERRO, null, null), out).situation()).isEqualTo(CaptureSituation.COM_ERRO);
        // NUNCA_TENTADA beats ATRASADA
        assertThat(CaptureSituationRules.company(true, new Verdict(CaptureSituation.NUNCA_TENTADA, null, null), late, out)
                .situation()).isEqualTo(CaptureSituation.NUNCA_TENTADA);
    }

    /** CT-CP-29 / CT-CP-30. */
    @Test
    void noChannelIsOutOfPlanAndABlockedFirmIsBlocked() {
        Verdict out = new Verdict(CaptureSituation.FORA_DO_PLANO, null, null);
        assertThat(CaptureSituationRules.company(true, out, out, out).situation()).isEqualTo(CaptureSituation.FORA_DO_PLANO);
        assertThat(CaptureSituationRules.company(false, out, out, out).situation()).isEqualTo(CaptureSituation.BLOQUEADA);
    }

    // ------------------------------------------------------------------ "tipo" (CP-RN-13)

    /** CT-CP-61: only the companies whose event stages failed or are stale. */
    @Test
    void eventsTypeFilter() {
        SiegFacts fine = sieg(1, 1, NOW.withHour(9), 1, 1, NOW.withHour(9));
        assertThat(CaptureSituationRules.eventsNeedAttention(fine, CLOCK)).isFalse();
        Map<CaptureStage, StageFacts> stages = Map.of(CaptureStage.SIEG_EVENTS_ISSUED,
                failure(CaptureStage.SIEG_EVENTS_ISSUED, CaptureFailureCode.SIEG_TIMEOUT, null, NOW.minusHours(1), 1));
        SiegFacts failing = siegWith(1, 1, NOW.withHour(9), 1, 1, NOW.withHour(9), TODAY, TODAY, TODAY, stages, null, null, null);
        assertThat(CaptureSituationRules.eventsNeedAttention(failing, CLOCK)).isTrue();
        assertThat(CaptureSituationRules.sideNeedsAttention(failing, false, CLOCK)).isFalse();
        SiegFacts pending = sieg(200, 180, NOW.withHour(9), 1, 1, NOW.withHour(9));
        assertThat(CaptureSituationRules.sideNeedsAttention(pending, false, CLOCK)).isTrue();
        assertThat(CaptureSituationRules.sideNeedsAttention(pending, true, CLOCK)).isFalse();
    }
}
