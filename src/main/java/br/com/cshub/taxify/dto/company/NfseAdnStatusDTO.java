package br.com.cshub.taxify.dto.company;

import java.time.LocalDateTime;

/**
 * How the company's NFS-e capture from the Ambiente Nacional is going.
 *
 * @param inPlan today's plan has the fiscal XML import with the national NFS-e integration
 */
public record NfseAdnStatusDTO(
        boolean inPlan,
        LocalDateTime lastRunAt,
        String lastStatus,
        String lastDetail,
        Integer lastNewNotes,
        LocalDateTime nextQueryAt,
        Long ultNsu
) {
}
