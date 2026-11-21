package br.com.cshub.taxify.dto.billing;

import br.com.cshub.taxify.enumerated.BillingProviderType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @param provider           where the firm's contracts/fees come from (MANUAL = typed in Taxify)
 * @param availableProviders every option the server supports, MANUAL included
 * @param connected          the chosen provider is authorized (always true for MANUAL)
 * @param feeCategoryIds     provider categories that count as fees; empty = all receivables
 */
public record BillingSettingsDTO(
        BillingProviderType provider,
        List<BillingProviderType> availableProviders,
        boolean connected,
        List<String> feeCategoryIds,
        LocalDateTime lastSyncAt
) {
}
