package br.com.cshub.taxify.dto.accountingfirm;

import jakarta.validation.constraints.NotBlank;

public record AccountingFirmDTO(
        String id,
        @NotBlank
        String name,
        String siegApiKey,
        Boolean deleted
) {
}
