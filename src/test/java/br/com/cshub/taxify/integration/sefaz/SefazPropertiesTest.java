package br.com.cshub.taxify.integration.sefaz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SefazPropertiesTest {

    @Test
    void everyUfByDefaultButNeverACompanyWithoutAValidOne() {
        SefazProperties properties = new SefazProperties();

        assertThat(properties.coversUf("PE")).isTrue();
        assertThat(properties.coversUf(" sp ")).isTrue();
        assertThat(properties.coversUf("RS")).isTrue();
        assertThat(properties.coversUf(null)).isFalse();
        assertThat(properties.coversUf("")).isFalse();
        assertThat(properties.coversUf("XX")).isFalse();
    }

    @Test
    void moreUfsOrAllOfThem() {
        SefazProperties properties = new SefazProperties();
        properties.setUfs(List.of("PE", "PB"));
        assertThat(properties.coversUf("PB")).isTrue();
        assertThat(properties.coversUf("SP")).isFalse();

        properties.setUfs(List.of());
        assertThat(properties.coversUf("SP")).isTrue();

        properties.setUfs(List.of(""));  // SEFAZ_DIST_UFS set but blank
        assertThat(properties.coversUf("SP")).isTrue();
    }
}
