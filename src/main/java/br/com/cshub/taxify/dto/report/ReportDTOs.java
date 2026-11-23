package br.com.cshub.taxify.dto.report;

import br.com.cshub.taxify.dto.assessment.AssessmentAlertDTO;
import br.com.cshub.taxify.dto.assessment.MonthRevenueDTO;
import br.com.cshub.taxify.dto.assessment.TaxEstimateDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Reports and simulation (roadmap M12). */
public final class ReportDTOs {

    private ReportDTOs() {
    }

    // ---------------------------------------------------------------- monthly report

    /** One direction of the month: notes, total and the main taxes highlighted on them. */
    public record DirectionTotals(long notes, BigDecimal total, BigDecimal icms, BigDecimal icmsSt, BigDecimal ipi,
                                  BigDecimal pis, BigDecimal cofins, long canceled) {
    }

    public record CfopTotal(String direction, String cfop, BigDecimal total) {
    }

    public record RetentionTotal(String nature, BigDecimal total) {
    }

    public record PendencyCount(String severity, long count) {
    }

    /**
     * Everything of one company and month for the client: revenue and estimated taxes (the assessment), entries and
     * exits by CFOP, retentions, open pendencies and audit findings, the 13-month evolution and the alerts.
     */
    public record Monthly(UUID companyId, String companyName, String companyDocument, String competence, String regime,
                          MonthRevenueDTO revenue, BigDecimal rbt12, long invoicesIssued, List<TaxEstimateDTO> taxes,
                          BigDecimal totalTaxes, DirectionTotals entries, DirectionTotals exits, List<CfopTotal> cfops,
                          List<RetentionTotal> retentions, List<PendencyCount> pendencies, long auditFindings,
                          List<MonthRevenueDTO> history, List<AssessmentAlertDTO> alerts) {
    }

    // ---------------------------------------------------------------- curva ABC

    /** @param share percent of the total; {@code accumulated} percent up to this line; {@code curve} A, B or C */
    public record AbcItem(String key, String name, String detail, BigDecimal value, long notes, BigDecimal share,
                          BigDecimal accumulated, String curve) {
    }

    /** @param by PRODUTO or FORNECEDOR; {@code from}/{@code to} the competences covered */
    public record Abc(String by, String from, String to, BigDecimal total, long countA, long countB, long countC,
                      List<AbcItem> items) {
    }

    // ---------------------------------------------------------------- simulation

    public record TaxLine(String code, String label, BigDecimal amount, String note) {
    }

    /** One regime over the 12 months: total, effective rate on the revenue and the taxes that make it. */
    public record RegimeResult(String regime, String label, BigDecimal total, BigDecimal effectiveRate, List<TaxLine> taxes,
                               List<String> notes, boolean cheapest) {
    }

    /**
     * @param revenue the net revenue of the 12 months the simulation covers
     * @param premises what was assumed - shown next to the result
     */
    public record Simulation(String companyName, String currentRegime, String from, String to, BigDecimal revenue,
                             BigDecimal profitMargin, BigDecimal issRate, List<RegimeResult> regimes, List<String> premises) {
    }
}
