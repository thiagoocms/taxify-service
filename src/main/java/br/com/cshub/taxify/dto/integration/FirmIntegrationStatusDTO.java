package br.com.cshub.taxify.dto.integration;

import br.com.cshub.taxify.enumerated.IntegrationType;

import java.time.LocalDateTime;

/**
 * What the front-end sees of one integration - never the credential itself.
 *
 * @param apiKeyBased configured by pasting an API key (SIEG, Acessórias) instead of an OAuth login
 * @param apiKeyHint  masked key ("••••1234") when one is set
 */
public record FirmIntegrationStatusDTO(
        IntegrationType type,
        boolean apiKeyBased,
        boolean configured,
        String apiKeyHint,
        LocalDateTime connectedAt,
        LocalDateTime lastSyncAt
) {
}
