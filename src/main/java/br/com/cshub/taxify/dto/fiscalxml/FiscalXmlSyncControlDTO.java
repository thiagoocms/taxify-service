package br.com.cshub.taxify.dto.fiscalxml;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Sync progress for one company, for the date window last checked: how many NFe SIEG
 * reports vs. how many are actually persisted - "de 10 a 11, tinha 30 na SIEG, importou
 * 30" is exactly dataEmissaoInicio/dataEmissaoFim + totalSieg/totalImportado/totalPendente.
 */
public record FiscalXmlSyncControlDTO(
        String companyId,
        String companyName,
        LocalDate dataEmissaoInicio,
        LocalDate dataEmissaoFim,
        int totalSieg,
        int totalImportado,
        int totalPendente,
        LocalDateTime ultimaVerificacaoEm
) {
}
