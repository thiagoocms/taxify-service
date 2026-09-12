package br.com.cshub.taxify.dto.invoice;

import jakarta.validation.constraints.NotBlank;

public record InvoiceAuditLogDTO(

        String id,
        @NotBlank
        String externalInvoiceKey,
        @NotBlank
        String userId,
        @NotBlank
        String companyId,
        @NotBlank
        String status,
        @NotBlank
        String notes,
        Boolean deleted
) {
}
