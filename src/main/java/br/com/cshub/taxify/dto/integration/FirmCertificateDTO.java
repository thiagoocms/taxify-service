package br.com.cshub.taxify.dto.integration;

import java.time.LocalDateTime;

/**
 * The accountant's e-CPF of the firm, as the screen sees it - never the file or the password, the CPF masked -
 * and how the last e-Fisco sync went. {@code present=false}: none, the e-Fisco sync doesn't run.
 */
public record FirmCertificateDTO(
        boolean present,
        String holder,
        String cpf,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        boolean expired,
        LocalDateTime lastSyncAt,
        String lastSyncStatus,
        String lastSyncDetail
) {
}
