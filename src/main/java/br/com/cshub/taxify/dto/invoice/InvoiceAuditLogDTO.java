package br.com.cshub.taxify.dto.invoice;

import java.time.LocalDateTime;
import jakarta.validation.constraints.NotBlank;

public record InvoiceAuditLogDTO(

        String id,
        @NotBlank
        String externalInvoiceKey,
        /** Read-only: the author - on create it is always the logged-in user. */
        String userId,
        @NotBlank
        String companyId,
        /** InvoiceAuditStatus name: OK, DIVERGENT, PENDING_DOCUMENTATION, NEEDS_RECTIFICATION, REJECTED... */
        @NotBlank
        String status,
        @NotBlank
        String notes,
        Boolean deleted,
        /** Read-only: when and by whom (null = a background job). */
        LocalDateTime createdDate,
        String userLogin
) {
}
