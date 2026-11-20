package br.com.cshub.taxify.dto.accountingfirm;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public record AccountingFirmDTO(
        String id,
        @NotBlank
        String name,
        String siegApiKey,
        String acessoriasApiToken,
        // Read-only: set by the Acessórias import itself, ignored on create/update.
        LocalDateTime acessoriasLastSyncAt,
        Boolean deleted
) {
}
