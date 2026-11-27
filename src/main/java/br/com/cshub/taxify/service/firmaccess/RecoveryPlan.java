package br.com.cshub.taxify.service.firmaccess;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Regra pura: o que recuperar quando o escritório volta a ter acesso total depois de um bloqueio de {@code blockedFrom}
 * até {@code reactivatedOn} (decisão do Tony sobre o QA-02). Só o que ficou fora da janela normal de cada rotina, e no
 * máximo {@code maxMonths} meses para trás a partir do mês da reativação.
 * <ul>
 *   <li>SIEG NF-e (emitidas e recebidas): a janela normal é o mês corrente (dia 1 até hoje) - recupera cada mês
 *       anterior do bloqueio, mês inteiro (as notas já guardadas são ignoradas na importação).</li>
 *   <li>SIEG NFS-e: a janela normal são os últimos {@value #NFSE_WINDOW_DAYS} dias - recupera o período do bloqueio
 *       antes dela, mês a mês.</li>
 *   <li>e-Fisco: a rotina normal busca o mês anterior e o corrente - recupera os meses do bloqueio antes disso.</li>
 * </ul>
 * Exemplo (CT1-22): bloqueio em 25/12, reativação em 04/01 → SIEG NF-e de dezembro; nada de NFS-e (dentro dos 35 dias)
 * nem de e-Fisco (dezembro é o mês anterior).
 */
public final class RecoveryPlan {

    /** O FiscalXmlSyncScheduler pede as NFS-e dos últimos 35 dias. */
    public static final int NFSE_WINDOW_DAYS = 35;

    public record Window(LocalDate start, LocalDate end) {
    }

    /**
     * @param notRecoveredMonths os meses do bloqueio além do limite - não são buscados, mas ficam registrados na trilha
     *                           (transparência, F1-RN-10): de {@code blockedFrom} até {@code current − maxMonths − 1}
     */
    public record Plan(List<YearMonth> nfeMonths, List<Window> nfseWindows, List<YearMonth> efiscoMonths,
                       List<YearMonth> notRecoveredMonths) {

        /** Nada a buscar (pode haver meses além do limite, que só vão para a trilha). */
        public boolean isEmpty() {
            return nfeMonths.isEmpty() && nfseWindows.isEmpty() && efiscoMonths.isEmpty();
        }
    }

    /** O primeiro mês que ainda se recupera: {@code current − maxMonths}. */
    public static YearMonth earliestRecoverable(LocalDate reactivatedOn, int maxMonths) {
        return YearMonth.from(reactivatedOn).minusMonths(maxMonths);
    }

    private RecoveryPlan() {
    }

    public static Plan plan(LocalDate blockedFrom, LocalDate reactivatedOn, int maxMonths) {
        if (blockedFrom == null || reactivatedOn == null || maxMonths < 1 || blockedFrom.isAfter(reactivatedOn)) {
            return new Plan(List.of(), List.of(), List.of(), List.of());
        }
        YearMonth current = YearMonth.from(reactivatedOn);
        YearMonth earliest = current.minusMonths(maxMonths);
        YearMonth first = max(YearMonth.from(blockedFrom), earliest);
        List<YearMonth> notRecovered = months(YearMonth.from(blockedFrom), earliest.minusMonths(1));

        List<YearMonth> nfe = months(first, current.minusMonths(1));
        List<YearMonth> efisco = months(first, current.minusMonths(2));

        List<Window> nfse = new ArrayList<>();
        LocalDate start = blockedFrom.isAfter(earliest.atDay(1)) ? blockedFrom : earliest.atDay(1);
        LocalDate end = reactivatedOn.minusDays(NFSE_WINDOW_DAYS + 1L);
        for (YearMonth month : months(YearMonth.from(start), YearMonth.from(end))) {
            LocalDate from = month.atDay(1).isBefore(start) ? start : month.atDay(1);
            LocalDate to = month.atEndOfMonth().isAfter(end) ? end : month.atEndOfMonth();
            if (!from.isAfter(to)) {
                nfse.add(new Window(from, to));
            }
        }
        return new Plan(nfe, nfse, efisco, notRecovered);
    }

    private static List<YearMonth> months(YearMonth from, YearMonth to) {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    private static YearMonth max(YearMonth a, YearMonth b) {
        return a.isAfter(b) ? a : b;
    }
}
