package br.com.cshub.taxify.dto.billing;

import br.com.cshub.taxify.enumerated.BillingProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The company's customer in the firm's billing provider. {@code provider} is read-only (the firm's). */
public record ExternalLinkDTO(
        BillingProviderType provider,
        @NotBlank @Size(max = 100) String externalId,
        @Size(max = 255) String externalName
) {
}
