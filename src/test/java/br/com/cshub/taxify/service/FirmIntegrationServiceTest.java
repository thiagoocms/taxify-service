package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.dto.integration.FirmIntegrationStatusDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FirmIntegrationServiceTest {

    @Test
    void keysAreNeverExposedWhole() {
        assertThat(FirmIntegrationService.maskKey("abcd-efgh-1234")).isEqualTo("••••1234");
        assertThat(FirmIntegrationService.maskKey("short")).isEqualTo("••••");
        assertThat(FirmIntegrationService.maskKey("  ")).isNull();
        assertThat(FirmIntegrationService.maskKey(null)).isNull();
    }

    @Test
    void statusOfAMissingIntegrationIsNotConfigured() {
        FirmIntegrationStatusDTO status = FirmIntegrationService.toStatus(IntegrationType.SIEG, null);

        assertThat(status.configured()).isFalse();
        assertThat(status.apiKeyBased()).isTrue();
        assertThat(FirmIntegrationService.toStatus(IntegrationType.CONTA_AZUL, null).apiKeyBased()).isFalse();
    }

    @Test
    void feeCategoryListRoundTripsAndIgnoresBlanksAndDuplicates() {
        FirmIntegration integration = new FirmIntegration();

        integration.setFeeCategoryIdList(Arrays.asList(" a ", "b", "", null, "a"));
        assertThat(integration.getFeeCategoryIds()).isEqualTo("a,b");
        assertThat(integration.getFeeCategoryIdList()).containsExactly("a", "b");

        integration.setFeeCategoryIdList(List.of());
        assertThat(integration.getFeeCategoryIds()).isNull();
        assertThat(integration.getFeeCategoryIdList()).isEmpty();
    }
}
