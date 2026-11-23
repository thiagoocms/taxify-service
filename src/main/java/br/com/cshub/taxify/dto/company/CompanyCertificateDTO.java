package br.com.cshub.taxify.dto.company;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The A1 certificate a company uses for the SEFAZ capture - its own or, for a branch, the
 * one of another company with the same CNPJ root ({@code inherited}) - and how the last
 * capture went. {@code present=false}: none, the capture skips the company.
 */
public record CompanyCertificateDTO(
        boolean present,
        boolean inherited,
        UUID ownerCompanyId,
        String ownerCompanyName,
        String subjectName,
        String cnpj,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        boolean expired,
        boolean encryptionConfigured,
        /** Today's plan has note import with SEFAZ as a source - without it the capture doesn't run. */
        boolean sefazInPlan,
        SefazStatus sefaz
) {

    public record SefazStatus(
            LocalDateTime lastRunAt,
            String lastStatus,
            String lastDetail,
            Integer lastNewNotes,
            Integer lastSummaries,
            LocalDateTime nextQueryAt,
            String ultNsu,
            String maxNsu
    ) {
    }
}
