package br.com.cshub.taxify.dto.accountingfirm;

import jakarta.validation.constraints.NotBlank;

/**
 * Integration credentials are not part of this DTO anymore - they're managed (and only
 * ever returned masked) through /integrations, see FirmIntegrationService.
 */
public record AccountingFirmDTO(
        String id,
        @NotBlank
        String name,
        Boolean deleted
) {
}
