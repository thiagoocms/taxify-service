package br.com.cshub.taxify.dto.reform;

import java.math.BigDecimal;

/**
 * One year of IBS/CBS reference rates as the firms read it (AL-RN-06): every status, with the legal basis and the
 * link to the act (law text, no client data), without version and audit.
 */
public record IbsCbsRateReferenceDTO(
        Integer year,
        BigDecimal cbsRate,
        BigDecimal ibsStateRate,
        BigDecimal ibsMunicipalRate,
        String status,
        boolean generatesFindings,
        String legalBasis,
        String sourceUrl
) {
}
