package br.com.cshub.taxify.service.pendency.alert;

import br.com.cshub.taxify.enumerated.PendencyType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Uma pendência ALTA aberta ainda não avisada ao usuário neste episódio (AV-RN-01, AV-RN-04). Só o que o resumo usa:
 * nunca o {@code title} nem o {@code detail} (podem ter número de nota e nome de terceiro - AV-RN-06).
 */
public record PendencyAlertCandidate(UUID pendencyId, UUID companyId, String companyName, PendencyType type,
                                     LocalDateTime firstSeenAt) {
}
