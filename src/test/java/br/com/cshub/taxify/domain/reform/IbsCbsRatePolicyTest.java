package br.com.cshub.taxify.domain.reform;

import br.com.cshub.taxify.domain.reform.IbsCbsRatePolicy.Kind;
import br.com.cshub.taxify.domain.reform.IbsCbsRatePolicy.Violation;
import br.com.cshub.taxify.enumerated.IbsCbsRateChangeAction;
import br.com.cshub.taxify.enumerated.IbsCbsRateStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** AL-RN-01 to AL-RN-05 (spec 2026-11-27, F1a-0), with the spec's numbers (2027: fictitious test rates). */
class IbsCbsRatePolicyTest {

    private static final String URL = "https://exemplo.test/ato";

    private static IbsCbsRateValues values(IbsCbsRateStatus status, String cbs, String ibsState, String ibsMunicipal,
                                           String basis, String url) {
        return IbsCbsRatePolicy.normalize(status, cbs == null ? null : new BigDecimal(cbs),
                ibsState == null ? null : new BigDecimal(ibsState), ibsMunicipal == null ? null : new BigDecimal(ibsMunicipal),
                basis, url);
    }

    private static Optional<Violation> check(int year, IbsCbsRateValues values) {
        return IbsCbsRatePolicy.check(year, values);
    }

    @Test
    void lawWithEverythingIsValidAndGeneratesFindings() {
        IbsCbsRateValues law = values(IbsCbsRateStatus.LAW, "8.8", "0.1", "0", " Ato fictício ", URL);
        assertThat(check(2027, law)).isEmpty();
        assertThat(law.generatesFindings()).isTrue();
        assertThat(law.cbsRate()).isEqualByComparingTo("8.8").hasToString("8.8000");
        assertThat(law.ibsMunicipalRate()).hasToString("0.0000");
        assertThat(law.legalBasis()).isEqualTo("Ato fictício");
    }

    /** CT-AL-04: ESTIMATE has the numbers but no finding (AL-RN-02). */
    @Test
    void estimateNeverGeneratesFindings() {
        IbsCbsRateValues estimate = values(IbsCbsRateStatus.ESTIMATE, "8.8", "0.1", "0", null, null);
        assertThat(check(2027, estimate)).isEmpty();
        assertThat(estimate.generatesFindings()).isFalse();
    }

    /** CT-AL-07: PENDING with only CBS is valid; the IBS stay null and nothing generates findings. */
    @Test
    void pendingMayHaveMissingRates() {
        IbsCbsRateValues pending = values(IbsCbsRateStatus.PENDING, "8.8", null, null, null, null);
        assertThat(check(2027, pending)).isEmpty();
        assertThat(pending.generatesFindings()).isFalse();
        assertThat(check(2027, values(IbsCbsRateStatus.ESTIMATE, "8.8", null, "0", null, null)))
                .contains(new Violation(Kind.RATE_INVALID, "ibsStateRate"));
    }

    /** CT-AL-08 and CT-AL-09: 5 decimals, negative and above 100 are refused, never rounded. */
    @Test
    void ratesOutOfRangeOrWithFiveDecimalsAreRefused() {
        assertThat(check(2027, values(IbsCbsRateStatus.ESTIMATE, "0.12345", "0.1", "0", null, null)))
                .contains(new Violation(Kind.RATE_INVALID, "cbsRate"));
        assertThat(check(2027, values(IbsCbsRateStatus.ESTIMATE, "-1", "0.1", "0", null, null)))
                .contains(new Violation(Kind.RATE_INVALID, "cbsRate"));
        assertThat(check(2027, values(IbsCbsRateStatus.ESTIMATE, "100.0001", "0.1", "0", null, null)))
                .contains(new Violation(Kind.RATE_INVALID, "cbsRate"));
        assertThat(check(2027, values(IbsCbsRateStatus.ESTIMATE, "100", "0.1", "0.12340", null, null))).isEmpty();
        assertThat(values(IbsCbsRateStatus.ESTIMATE, "0.12345", null, null, null, null).cbsRate()).hasToString("0.12345");
    }

    /** CT-AL-10. */
    @Test
    void yearOutside2026To2040IsRefused() {
        IbsCbsRateValues valid = values(IbsCbsRateStatus.PENDING, null, null, null, null, null);
        assertThat(check(2025, valid)).contains(new Violation(Kind.YEAR_INVALID, null));
        assertThat(check(2041, valid)).contains(new Violation(Kind.YEAR_INVALID, null));
        assertThat(check(2026, valid)).isEmpty();
        assertThat(check(2040, valid)).isEmpty();
    }

    /** CA-AL-04 and CT-AL-11: LAW needs the legal basis and an https:// link. */
    @Test
    void lawNeedsBasisAndHttpsLink() {
        assertThat(check(2027, values(IbsCbsRateStatus.LAW, "8.8", "0.1", "0", "Ato", null)))
                .contains(new Violation(Kind.BASIS_REQUIRED, null));
        assertThat(check(2027, values(IbsCbsRateStatus.LAW, "8.8", "0.1", "0", "  ", URL)))
                .contains(new Violation(Kind.BASIS_REQUIRED, null));
        assertThat(check(2027, values(IbsCbsRateStatus.LAW, "8.8", "0.1", "0", "Ato", "http://exemplo.test")))
                .contains(new Violation(Kind.BASIS_REQUIRED, null));
        assertThat(check(2027, values(IbsCbsRateStatus.LAW, "8.8", "0.1", "0", "x".repeat(501), URL)))
                .contains(new Violation(Kind.BASIS_REQUIRED, null));
        // other status: optional, same limits
        assertThat(check(2027, values(IbsCbsRateStatus.ESTIMATE, "8.8", "0.1", "0", null, "http://exemplo.test")))
                .contains(new Violation(Kind.BASIS_INVALID, null));
        assertThat(check(2027, values(IbsCbsRateStatus.ESTIMATE, "8.8", "0.1", "0", "x".repeat(500), URL))).isEmpty();
    }

    @Test
    void unknownStatusIsRefused() {
        assertThat(IbsCbsRateStatus.parse("LEI")).isEmpty();
        assertThat(IbsCbsRateStatus.parse(" law ")).contains(IbsCbsRateStatus.LAW);
        assertThat(check(2027, values(null, "8.8", "0.1", "0", null, null))).contains(new Violation(Kind.STATUS_INVALID, null));
    }

    /** CA-AL-06 and CT-AL-17: the current and the next year of Recife without LAW. */
    @Test
    void warningsForTheCurrentAndTheNextYearWithoutLaw() {
        assertThat(IbsCbsRatePolicy.yearsWithoutLaw(LocalDate.of(2026, 12, 15), List.of(2026))).containsExactly(2027);
        assertThat(IbsCbsRatePolicy.yearsWithoutLaw(LocalDate.of(2027, 1, 2), List.of(2026))).containsExactly(2027, 2028);
        assertThat(IbsCbsRatePolicy.yearsWithoutLaw(LocalDate.of(2026, 12, 31), List.of())).containsExactly(2026, 2027);
        assertThat(IbsCbsRatePolicy.yearsWithoutLaw(LocalDate.of(2027, 1, 1), List.of(2026))).containsExactly(2027, 2028);
        assertThat(IbsCbsRatePolicy.yearsWithoutLaw(LocalDate.of(2027, 1, 1), List.of(2027, 2028))).isEmpty();
    }

    /** AL-RN-04 and CT-AL-14/15: no event without a change; before and after otherwise. */
    @Test
    void changeEventOnlyWhenSomethingChanged() {
        IbsCbsRateValues estimate = values(IbsCbsRateStatus.ESTIMATE, "8.8", "0.1", "0", null, null);
        IbsCbsRateValues sameNumbers = values(IbsCbsRateStatus.ESTIMATE, "8.80", "0.1000", "0.0", null, null);
        LocalDateTime at = LocalDateTime.of(2026, 12, 20, 10, 0);
        assertThat(IbsCbsRateChange.between(2027, estimate, sameNumbers, "csadmin", at)).isEmpty();

        IbsCbsRateValues law = values(IbsCbsRateStatus.LAW, "8.8", "0.1", "0", "Ato fictício", URL);
        IbsCbsRateChange updated = IbsCbsRateChange.between(2027, estimate, law, "csadmin", at).orElseThrow();
        assertThat(updated.getAction()).isEqualTo(IbsCbsRateChangeAction.UPDATED);
        assertThat(updated.getStatusBefore()).isEqualTo(IbsCbsRateStatus.ESTIMATE);
        assertThat(updated.getStatusAfter()).isEqualTo(IbsCbsRateStatus.LAW);
        assertThat(updated.getSourceUrlAfter()).isEqualTo(URL);
        assertThat(updated.getChangedBy()).isEqualTo("csadmin");
        assertThat(updated.getChangedAt()).isEqualTo(at);

        IbsCbsRateChange created = IbsCbsRateChange.between(2027, null, estimate, "csadmin", at).orElseThrow();
        assertThat(created.getAction()).isEqualTo(IbsCbsRateChangeAction.CREATED);
        assertThat(created.getStatusBefore()).isNull();
    }

    /** The old 4-argument constructor (conferência tests) is an official year. */
    @Test
    void legacyConstructorIsLaw() {
        IbsCbsRate rate = new IbsCbsRate(2026, new BigDecimal("0.9"), new BigDecimal("0.1"), BigDecimal.ZERO);
        assertThat(rate.getStatus()).isEqualTo(IbsCbsRateStatus.LAW);
        assertThat(rate.generatesFindings()).isTrue();
    }
}
