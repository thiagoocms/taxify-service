package br.com.cshub.taxify.dto.billing;

import br.com.cshub.taxify.enumerated.BillingProviderType;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BillingSettingsUpdateDTO(@NotNull BillingProviderType provider, List<String> feeCategoryIds) {
}
