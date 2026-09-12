package br.com.cshub.taxify.dto.invoice;

public record InvoiceAuditLogFilterDTO(
        String id,
        String externalInvoiceKey,
        String userId,
        String companyId,
        String status,
        String notes,
        Boolean deleted
) {
}
