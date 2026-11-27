package br.com.cshub.taxify.dto.reform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One year of IBS/CBS reference rates in the platform console (AL-RN-04): the row of {@code GET} and the body of
 * {@code PUT /platform/ibs-cbs-rates/{year}}. Rates in percent with 4 decimals ({@code 0.9000} = 0.9%). In the body,
 * {@code year} comes from the path, {@code version} is null to create and the stored one to update;
 * {@code generatesFindings}, {@code changedBy} and {@code changedAt} are ignored.
 */
public record IbsCbsRateDTO(
        Integer year,
        BigDecimal cbsRate,
        BigDecimal ibsStateRate,
        BigDecimal ibsMunicipalRate,
        String status,
        Boolean generatesFindings,
        String legalBasis,
        String sourceUrl,
        Integer version,
        String changedBy,
        OffsetDateTime changedAt
) {

    /** The same body with the year of the path. */
    public IbsCbsRateDTO withYear(Integer pathYear) {
        return new IbsCbsRateDTO(pathYear, cbsRate, ibsStateRate, ibsMunicipalRate, status, generatesFindings, legalBasis,
                sourceUrl, version, changedBy, changedAt);
    }
}
