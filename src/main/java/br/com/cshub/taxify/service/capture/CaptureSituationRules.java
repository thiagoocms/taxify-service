package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.enumerated.CaptureChannel;
import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureSituation;
import br.com.cshub.taxify.enumerated.CaptureStage;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A situação de cada canal e da empresa no painel da captura (CP-RN-06 a CP-RN-08), uma função pura: sem I/O, sem
 * relógio (o "agora" e o "hoje" de Recife vêm de fora). Vale a primeira linha da tabela que se aplica. O motivo sai
 * como chave do i18n e argumentos ({@link Reason}); quem monta a resposta resolve o texto.
 *
 * <p>Nenhuma regra fiscal: "em dia" e "atrasada" são decisões de produto (P-CP-03), com as janelas configuráveis.
 */
public final class CaptureSituationRules {

    private CaptureSituationRules() {
    }

    /** Chave do i18n ({@code capture.reason.*} ou {@code capture.failure.*}) e os argumentos já formatáveis. */
    public record Reason(String key, Object... args) {
    }

    public record Verdict(CaptureSituation situation, Reason reason, CaptureFailureCode failureCode) {

        static Verdict of(CaptureSituation situation, Reason reason) {
            return new Verdict(situation, reason, null);
        }
    }

    /** O último resultado de uma etapa (capture_stage_status). */
    public record StageFacts(CaptureStage stage, boolean failure, LocalDateTime outcomeAt, LocalDateTime lastSuccessAt,
                             LocalDateTime lastFailureAt, CaptureFailureCode failureCode, String providerStatus,
                             CaptureFailureCode warningCode, Integer warningCount, int consecutiveFailures) {
    }

    /** Janelas configuráveis (P-CP-03) e o relógio de Recife. */
    public record Clock(LocalDateTime now, LocalDate today, int siegFreshHours, int directFreshHours) {
    }

    /** Os fatos do canal SIEG de uma empresa (fiscal_xml_sync_control, direito, etapas). */
    public record SiegFacts(boolean firmAllowed, boolean entitled, boolean hasSubscription, LocalDateTime lastAttemptAt,
                            int receivedTotalSieg, int receivedImported, LocalDateTime receivedCheckedAt,
                            Integer issuedTotalSieg, Integer issuedImported, LocalDateTime issuedCheckedAt,
                            LocalDate receivedEventsOn, LocalDate issuedEventsOn, LocalDate serviceInvoicesOn,
                            String receivedPrevMonth, Integer receivedPrevTotalSieg, Integer receivedPrevImported,
                            String issuedPrevMonth, Integer issuedPrevTotalSieg, Integer issuedPrevImported,
                            Map<CaptureStage, StageFacts> stages, Integer queuePosition) {

        /** A verificação completa: a mais antiga das duas; nula enquanto um lado nunca foi verificado. */
        public LocalDateTime verifiedAt() {
            if (receivedCheckedAt == null || issuedCheckedAt == null) {
                return null;
            }
            return issuedCheckedAt.isBefore(receivedCheckedAt) ? issuedCheckedAt : receivedCheckedAt;
        }
    }

    /** Os fatos de SEFAZ ou ADN (controle, certificado, direito, etapa). */
    public record DirectFacts(CaptureChannel channel, boolean firmAllowed, boolean planHasService, boolean hasSubscription,
                              boolean ufMissing, boolean ufCovered, boolean cnpjMissing, boolean certificatePresent,
                              LocalDate certificateValidUntil, boolean controlRow, LocalDateTime lastRunAt, String lastStatus,
                              StageFacts stage) {
    }

    // ------------------------------------------------------------------ SIEG (CP-RN-06)

    public static Verdict sieg(SiegFacts facts, Clock clock) {
        if (!facts.firmAllowed()) {
            return Verdict.of(CaptureSituation.BLOQUEADA, new Reason("capture.reason.blocked"));
        }
        if (!facts.entitled()) {
            return Verdict.of(CaptureSituation.FORA_DO_PLANO, new Reason(facts.hasSubscription()
                    ? "capture.reason.sieg.not.in.plan" : "capture.reason.no.subscription"));
        }
        if (facts.lastAttemptAt() == null) {
            return Verdict.of(CaptureSituation.NUNCA_TENTADA, facts.queuePosition() == null
                    ? new Reason("capture.reason.never.attempted.no.position")
                    // the screen shows the position plus 1 (CP-RN-12): "Posição: 1" is the next one
                    : new Reason("capture.reason.never.attempted", facts.queuePosition() + 1));
        }
        StageFacts failing = mostRecentFailure(facts.stages(), CaptureChannel.SIEG);
        if (failing != null) {
            return new Verdict(CaptureSituation.COM_ERRO, failureReason(failing, null), failing.failureCode());
        }
        Reason late = siegLate(facts, clock);
        if (late != null) {
            return Verdict.of(CaptureSituation.ATRASADA, late);
        }
        Reason pending = siegPending(facts, clock);
        if (pending != null) {
            return Verdict.of(CaptureSituation.PENDENTE, pending);
        }
        return Verdict.of(CaptureSituation.EM_DIA, null);
    }

    /** CP-RN-06 #5: sem verificação completa na janela, ou eventos/NFS-e sem varredura desde antes de ontem. */
    static Reason siegLate(SiegFacts facts, Clock clock) {
        LocalDateTime verifiedAt = facts.verifiedAt();
        if (verifiedAt == null) {
            return new Reason("capture.reason.never.verified");
        }
        Duration age = Duration.between(verifiedAt, clock.now());
        if (age.compareTo(Duration.ofHours(clock.siegFreshHours())) > 0) {
            return new Reason("capture.reason.verification.late", age.toHours());
        }
        LocalDate yesterday = clock.today().minusDays(1);
        LocalDate oldest = null;
        boolean never = false;
        for (LocalDate day : new LocalDate[]{facts.receivedEventsOn(), facts.issuedEventsOn(), facts.serviceInvoicesOn()}) {
            if (day == null) {
                never = true;
            } else if (day.isBefore(yesterday) && (oldest == null || day.isBefore(oldest))) {
                oldest = day;
            }
        }
        if (oldest != null) {
            return new Reason("capture.reason.sweep.late", oldest);
        }
        if (never) {
            return new Reason("capture.reason.sweep.never");
        }
        return null;
    }

    /** CP-RN-06 #6: um lado verificado no mês corrente com importado &lt; total SIEG, ou o mês anterior fechado com falta. */
    static Reason siegPending(SiegFacts facts, Clock clock) {
        int receivedMissing = missingThisMonth(facts.receivedCheckedAt(), facts.receivedTotalSieg(), facts.receivedImported(), clock);
        int issuedMissing = missingThisMonth(facts.issuedCheckedAt(), facts.issuedTotalSieg(), facts.issuedImported(), clock);
        if (receivedMissing > 0 && issuedMissing > 0) {
            return new Reason("capture.reason.missing.both", receivedMissing, issuedMissing);
        }
        if (receivedMissing > 0) {
            return new Reason("capture.reason.missing.received", receivedMissing);
        }
        if (issuedMissing > 0) {
            return new Reason("capture.reason.missing.issued", issuedMissing);
        }
        String previous = YearMonth.from(clock.today()).minusMonths(1).toString();
        int prevMissing = missingPrevious(previous, facts.receivedPrevMonth(), facts.receivedPrevTotalSieg(), facts.receivedPrevImported())
                + missingPrevious(previous, facts.issuedPrevMonth(), facts.issuedPrevTotalSieg(), facts.issuedPrevImported());
        if (prevMissing > 0) {
            return new Reason("capture.reason.previous.month.missing", YearMonth.parse(previous), prevMissing);
        }
        return null;
    }

    /** total SIEG − importado, só quando a verificação do lado é do mês corrente; importado &gt; total não é pendência. */
    public static int missingThisMonth(LocalDateTime checkedAt, Integer totalSieg, Integer imported, Clock clock) {
        if (checkedAt == null || totalSieg == null || !YearMonth.from(checkedAt).equals(YearMonth.from(clock.today()))) {
            return 0;
        }
        int done = imported == null ? 0 : imported;
        return Math.max(0, totalSieg - done);
    }

    /** O mês anterior fechou com falta (CP-RN-22) - só o mês imediatamente anterior ao corrente, até a virada seguinte. */
    public static int missingPrevious(String previousMonth, String month, Integer totalSieg, Integer imported) {
        if (month == null || totalSieg == null || !previousMonth.equals(month.strip())) {
            return 0;
        }
        return Math.max(0, totalSieg - (imported == null ? 0 : imported));
    }

    // ------------------------------------------------------------------ SEFAZ and ADN (CP-RN-07)

    public static Verdict direct(DirectFacts facts, Clock clock) {
        if (!facts.firmAllowed()) {
            return Verdict.of(CaptureSituation.BLOQUEADA, new Reason("capture.reason.blocked"));
        }
        String channel = facts.channel().name().toLowerCase(Locale.ROOT);
        if (!facts.planHasService()) {
            return Verdict.of(CaptureSituation.FORA_DO_PLANO, new Reason(facts.hasSubscription()
                    ? "capture.reason." + channel + ".not.in.plan" : "capture.reason.no.subscription"));
        }
        if (facts.channel() == CaptureChannel.SEFAZ && !facts.ufMissing() && !facts.ufCovered()) {
            return Verdict.of(CaptureSituation.FORA_DO_PLANO, new Reason("capture.reason.sefaz.uf.not.covered"));
        }
        if (facts.cnpjMissing()) {
            return failure(CaptureFailureCode.COMPANY_WITHOUT_CNPJ, null);
        }
        if (facts.channel() == CaptureChannel.SEFAZ && facts.ufMissing()) {
            return failure(CaptureFailureCode.COMPANY_WITHOUT_UF, null);
        }
        if (!facts.certificatePresent()) {
            return failure(CaptureFailureCode.CERT_MISSING, null);
        }
        if (facts.certificateValidUntil() != null && facts.certificateValidUntil().isBefore(clock.today())) {
            // the date comes from company_certificates, never from the capture's text
            return failure(CaptureFailureCode.CERT_EXPIRED, facts.certificateValidUntil());
        }
        if ("ERRO".equals(facts.lastStatus())) {
            StageFacts stage = facts.stage();
            if (stage == null || !stage.failure() || stage.failureCode() == null) {
                // ERRO recorded before the panel existed: never last_detail (CP-RN-07)
                return failure(CaptureFailureCode.LEGACY_ERROR, null);
            }
            return new Verdict(CaptureSituation.COM_ERRO, failureReason(stage, facts.certificateValidUntil()), stage.failureCode());
        }
        if (!facts.controlRow() || facts.lastRunAt() == null) {
            return Verdict.of(CaptureSituation.NUNCA_TENTADA, new Reason("capture.reason.direct.never"));
        }
        Duration age = Duration.between(facts.lastRunAt(), clock.now());
        if (age.compareTo(Duration.ofHours(clock.directFreshHours())) > 0) {
            return Verdict.of(CaptureSituation.ATRASADA, new Reason("capture.reason.direct.late", age.toHours()));
        }
        return Verdict.of(CaptureSituation.EM_DIA, null);
    }

    private static Verdict failure(CaptureFailureCode code, Object arg) {
        return new Verdict(CaptureSituation.COM_ERRO, arg == null ? new Reason(code.messageKey()) : new Reason(code.messageKey(), arg), code);
    }

    /** O certificado vence nos próximos {@code days} dias (aviso - não muda a situação). */
    public static boolean expiresSoon(LocalDate validUntil, LocalDate today, int days) {
        return validUntil != null && !validUntil.isBefore(today) && !validUntil.isAfter(today.plusDays(days));
    }

    // ------------------------------------------------------------------ the company (CP-RN-08)

    /**
     * BLOQUEADA com o escritório bloqueado; FORA_DO_PLANO sem nenhum canal com direito; senão a pior situação entre os
     * canais com direito: COM_ERRO &gt; NUNCA_TENTADA &gt; ATRASADA &gt; PENDENTE &gt; EM_DIA. Empate: SIEG, SEFAZ, ADN.
     */
    public static Verdict company(boolean firmAllowed, Verdict sieg, Verdict sefaz, Verdict adn) {
        if (!firmAllowed) {
            return Verdict.of(CaptureSituation.BLOQUEADA, new Reason("capture.reason.blocked"));
        }
        Verdict worst = null;
        for (Verdict verdict : List.of(sieg, sefaz, adn)) {
            if (verdict.situation() == CaptureSituation.FORA_DO_PLANO || verdict.situation() == CaptureSituation.BLOQUEADA) {
                continue;
            }
            if (worst == null || verdict.situation().severity() > worst.situation().severity()) {
                worst = verdict;
            }
        }
        if (worst == null) {
            return Verdict.of(CaptureSituation.FORA_DO_PLANO, new Reason("capture.reason.no.channel"));
        }
        return worst;
    }

    // ------------------------------------------------------------------ stages

    /** A etapa com falha (último resultado FAILURE) mais recente do canal; null = nenhuma. */
    public static StageFacts mostRecentFailure(Map<CaptureStage, StageFacts> stages, CaptureChannel channel) {
        if (stages == null) {
            return null;
        }
        return stages.values().stream()
                .filter(Objects::nonNull)
                .filter(stage -> stage.stage().channel() == channel && stage.failure())
                .max(Comparator.comparing(StageFacts::outcomeAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** A última falha da empresa, de qualquer etapa (resolvida ou não): a de last_failure_at mais recente. */
    public static StageFacts lastFailure(Map<CaptureStage, StageFacts> stages) {
        if (stages == null) {
            return null;
        }
        Stream<StageFacts> withFailure = stages.values().stream().filter(Objects::nonNull).filter(stage -> stage.lastFailureAt() != null);
        List<StageFacts> unresolved = new ArrayList<>();
        List<StageFacts> all = new ArrayList<>();
        withFailure.forEach(stage -> {
            all.add(stage);
            if (stage.failure()) {
                unresolved.add(stage);
            }
        });
        Comparator<StageFacts> byTime = Comparator.comparing(StageFacts::lastFailureAt);
        // an open failure first - it is what the screen has to show; then the most recent resolved one
        return unresolved.stream().max(byTime).orElseGet(() -> all.stream().max(byTime).orElse(null));
    }

    /** Os códigos cuja mensagem leva o status HTTP / cStat ({0}). */
    static final java.util.Set<CaptureFailureCode> WITH_STATUS = java.util.EnumSet.of(CaptureFailureCode.SIEG_AUTH,
            CaptureFailureCode.SIEG_REJECTED, CaptureFailureCode.SIEG_UNAVAILABLE, CaptureFailureCode.SEFAZ_REJECTED,
            CaptureFailureCode.ADN_REJECTED);

    /**
     * A mensagem do código (CP-RN-11), a mesma em todo lugar (motivo, canal, última falha, etapas): o status entra nos
     * códigos que o usam e a validade do certificado (de company_certificates) no CERT_EXPIRED. Sem o valor - status
     * não informado, certificado sem data - sai a variante sem ele ({@code .nostatus} / {@code .nodate}), nunca "()".
     */
    public static Reason failureReason(StageFacts stage, LocalDate certificateValidUntil) {
        CaptureFailureCode code = stage.failureCode() == null ? CaptureFailureCode.INTERNAL_ERROR : stage.failureCode();
        if (code == CaptureFailureCode.CERT_EXPIRED) {
            return certificateValidUntil == null ? new Reason(code.messageKey() + ".nodate")
                    : new Reason(code.messageKey(), certificateValidUntil);
        }
        if (WITH_STATUS.contains(code)) {
            return stage.providerStatus() == null ? new Reason(code.messageKey() + ".nostatus")
                    : new Reason(code.messageKey(), stage.providerStatus());
        }
        return new Reason(code.messageKey());
    }

    public static Map<CaptureStage, StageFacts> index(List<StageFacts> stages) {
        Map<CaptureStage, StageFacts> map = new EnumMap<>(CaptureStage.class);
        for (StageFacts stage : stages) {
            map.put(stage.stage(), stage);
        }
        return map;
    }

    // ------------------------------------------------------------------ "tipo" filter (CP-RN-13)

    /** NF-e recebidas ou emitidas: a etapa do lado com falha, ou o lado pendente ou atrasado. */
    public static boolean sideNeedsAttention(SiegFacts facts, boolean issued, Clock clock) {
        if (!facts.firmAllowed() || !facts.entitled()) {
            return false;
        }
        StageFacts stage = facts.stages() == null ? null : facts.stages().get(CaptureStage.siegSide(issued));
        if (stage != null && stage.failure()) {
            return true;
        }
        LocalDateTime checkedAt = issued ? facts.issuedCheckedAt() : facts.receivedCheckedAt();
        if (checkedAt == null || Duration.between(checkedAt, clock.now()).compareTo(Duration.ofHours(clock.siegFreshHours())) > 0) {
            return true;
        }
        return issued ? missingThisMonth(checkedAt, facts.issuedTotalSieg(), facts.issuedImported(), clock) > 0
                : missingThisMonth(checkedAt, facts.receivedTotalSieg(), facts.receivedImported(), clock) > 0;
    }

    /** Eventos: as duas etapas de eventos - com falha, ou sem varredura desde antes de ontem. */
    public static boolean eventsNeedAttention(SiegFacts facts, Clock clock) {
        if (!facts.firmAllowed() || !facts.entitled()) {
            return false;
        }
        return stageFailed(facts, CaptureStage.SIEG_EVENTS_RECEIVED) || stageFailed(facts, CaptureStage.SIEG_EVENTS_ISSUED)
                || stale(facts.receivedEventsOn(), clock) || stale(facts.issuedEventsOn(), clock);
    }

    /** NFS-e: a etapa SIEG_NFSE (falha ou sem varredura) ou o canal ADN pendente, atrasado ou com erro. */
    public static boolean serviceInvoicesNeedAttention(SiegFacts facts, Verdict adn, Clock clock) {
        boolean sieg = facts.firmAllowed() && facts.entitled()
                && (stageFailed(facts, CaptureStage.SIEG_NFSE) || stale(facts.serviceInvoicesOn(), clock));
        return sieg || (adn != null && adn.situation().needsAttention());
    }

    private static boolean stageFailed(SiegFacts facts, CaptureStage stage) {
        StageFacts found = facts.stages() == null ? null : facts.stages().get(stage);
        return found != null && found.failure();
    }

    private static boolean stale(LocalDate day, Clock clock) {
        return day == null || day.isBefore(clock.today().minusDays(1));
    }
}
