package br.com.cshub.taxify.domain.reform;

import br.com.cshub.taxify.enumerated.IbsCbsRateStatus;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The editable content of one year of IBS/CBS reference rates (value object): status, the three rates in percent
 * (scale 4, never rounded) and where they come from. Two values are equal when every field is - rates compared by
 * number ({@code 0.9} = {@code 0.9000}) - which is what decides whether a save is a change (AL-RN-04).
 */
public record IbsCbsRateValues(IbsCbsRateStatus status, BigDecimal cbsRate, BigDecimal ibsStateRate,
                               BigDecimal ibsMunicipalRate, String legalBasis, String sourceUrl) {

    public boolean sameAs(IbsCbsRateValues other) {
        return other != null && status == other.status
                && sameNumber(cbsRate, other.cbsRate) && sameNumber(ibsStateRate, other.ibsStateRate)
                && sameNumber(ibsMunicipalRate, other.ibsMunicipalRate)
                && Objects.equals(legalBasis, other.legalBasis) && Objects.equals(sourceUrl, other.sourceUrl);
    }

    /** True only with LAW and the three rates filled in: the only rows the conferência compares with (AL-RN-02). */
    public boolean generatesFindings() {
        return status == IbsCbsRateStatus.LAW && cbsRate != null && ibsStateRate != null && ibsMunicipalRate != null;
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
