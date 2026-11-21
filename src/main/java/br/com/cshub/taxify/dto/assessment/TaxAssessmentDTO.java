package br.com.cshub.taxify.dto.assessment;

import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.TaxRegime;

import java.math.BigDecimal;
import java.util.List;

/**
 * Period assessment of one company: revenue of the month, RBT12 and year-to-date, estimated
 * taxes for its regime and alerts. An estimate from the notes in Taxify - not the PGDAS/DCTF.
 */
public record TaxAssessmentDTO(
        String companyId,
        String companyName,
        String companyDocument,
        String month,
        TaxRegime taxRegime,
        String taxRegimeLabel,
        BusinessActivity businessActivity,
        SimplesAnnex simplesServiceAnnex,
        MonthRevenueDTO revenue,
        long invoicesIssued,
        /** Revenue of the 12 months before the period (Simples). */
        BigDecimal rbt12,
        /** True when RBT12 was proportionalized - less than 12 months of notes before the period. */
        boolean rbt12Proportional,
        BigDecimal yearToDate,
        List<TaxEstimateDTO> taxes,
        BigDecimal totalTaxes,
        List<AssessmentAlertDTO> alerts,
        /** The 12 months before plus the period itself; empty on the firm-wide list. */
        List<MonthRevenueDTO> history,
        /** Fechamento of the month; when CLOSED, every value above is the one frozen at closing. */
        PeriodClosingDTO closing
) {

    public TaxAssessmentDTO withClosing(PeriodClosingDTO closing, boolean keepHistory) {
        return new TaxAssessmentDTO(companyId, companyName, companyDocument, month, taxRegime, taxRegimeLabel,
                businessActivity, simplesServiceAnnex, revenue, invoicesIssued, rbt12, rbt12Proportional, yearToDate,
                taxes, totalTaxes, alerts, keepHistory && history != null ? history : List.of(), closing);
    }
}
