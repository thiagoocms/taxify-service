package br.com.cshub.taxify.domain.fiscaldocument;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CF-RN-01 to 06 and 09 (spec 2026-11-27, filtro de CFOP múltiplo), with the numbers of section 12. */
class CfopFilterTest {

    /** CF-RN-01. */
    @Test
    void onlyTheDigitsAreKept() {
        assertThat(CfopFilter.normalize("1.102")).isEqualTo("1102");
        assertThat(CfopFilter.normalize(" 5102 ")).isEqualTo("5102");
        assertThat(CfopFilter.normalize(null)).isEmpty();
    }

    /** CT-11 (CF-RN-01, 03, 05): normalized, without the duplicate, ascending. */
    @Test
    void theCanonicalListIsSortedAndWithoutDuplicates() {
        assertThat(CfopFilter.of(List.of("6102", "5.102", "5102")).values()).containsExactly("5102", "6102");
    }

    @Test
    void nullOrEmptyIsNoFilter() {
        assertThat(CfopFilter.of(null).isEmpty()).isTrue();
        assertThat(CfopFilter.of(List.of()).isEmpty()).isTrue();
        assertThat(CfopFilter.none().valuesOrSentinel()).containsExactly("");
    }

    /** CT-21 (CF-RN-02): the values as they came, an empty one included. */
    @Test
    void aValueWithoutFourDigitsIsInvalid() {
        assertThatThrownBy(() -> CfopFilter.of(Arrays.asList("1102", "11", "", null, "11022")))
                .isInstanceOfSatisfying(CfopFilter.Invalid.class, invalid -> {
                    assertThat(invalid.reason()).isEqualTo(CfopFilter.Invalid.Reason.INVALID);
                    assertThat(invalid.invalidValues()).containsExactly("11", "", "", "11022");
                });
    }

    @Test
    void atMostTwentyInvalidValuesAreReported() {
        List<String> invalid = IntStream.range(0, 30).mapToObj(String::valueOf).toList();
        assertThatThrownBy(() -> CfopFilter.of(invalid))
                .isInstanceOfSatisfying(CfopFilter.Invalid.class, error -> assertThat(error.invalidValues()).hasSize(20));
    }

    /** CT-22 (CF-RN-04, 05): 21 distinct is too many; 21 with two equal (20 distinct) passes. */
    @Test
    void moreThanTwentyDistinctIsTooMany() {
        List<String> distinct = IntStream.rangeClosed(1101, 1121).mapToObj(String::valueOf).toList();
        assertThatThrownBy(() -> CfopFilter.of(distinct))
                .isInstanceOfSatisfying(CfopFilter.Invalid.class,
                        error -> assertThat(error.reason()).isEqualTo(CfopFilter.Invalid.Reason.TOO_MANY));

        List<String> withDuplicate = new ArrayList<>(IntStream.rangeClosed(1101, 1120).mapToObj(String::valueOf).toList());
        withDuplicate.add("1.101");
        assertThat(CfopFilter.of(withDuplicate).values()).hasSize(20);
    }

    /** CT-18, CT-19, CT-20 (CF-RN-09). */
    @Test
    void theOldFieldOnlyWhenTheListIsEmptyAndNeverChecked() {
        assertThat(CfopFilter.resolve(null, "5102").values()).containsExactly("5102");
        assertThat(CfopFilter.resolve(List.of(), "51").values()).containsExactly("51");
        assertThat(CfopFilter.resolve(null, "abc").isEmpty()).isTrue();
        assertThat(CfopFilter.resolve(null, " ").isEmpty()).isTrue();
        assertThat(CfopFilter.resolve(List.of("5102"), "6102").values()).containsExactly("5102");
        assertThat(CfopFilter.usesLegacy(null, "5102")).isTrue();
        assertThat(CfopFilter.usesLegacy(List.of("5102"), "6102")).isFalse();
        assertThat(CfopFilter.usesLegacy(null, "")).isFalse();
    }

    /** CF-RN-06 and 21: at least one in common; the empty filter matches everything. */
    @Test
    void aNoteMatchesWithAnyOfTheCfops() {
        CfopFilter filter = CfopFilter.of(List.of("6102", "6403"));

        assertThat(filter.matchesAny(List.of("6403"))).isTrue();
        assertThat(filter.matchesAny(List.of(" 6102 ", "6949"))).isTrue();
        assertThat(filter.matchesAny(List.of("6949"))).isFalse();
        assertThat(filter.matchesAny(List.of())).isFalse();
        assertThat(filter.matchesAny(null)).isFalse();
        assertThat(CfopFilter.none().matchesAny(List.of())).isTrue();
    }
}
