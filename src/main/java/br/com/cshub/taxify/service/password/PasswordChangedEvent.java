package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.enumerated.PasswordChangeChannel;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A senha de um usuário mudou (SN-RN-06, SN-RN-09, SN-RN-10). Publicado dentro da transação da troca; o
 * {@link PasswordChangeNotifier} registra o log e manda o aviso só depois do commit.
 *
 * @param changedAt hora da troca no relógio de Recife (vai no texto do aviso)
 */
public record PasswordChangedEvent(UUID userId, PasswordChangeChannel channel, LocalDateTime changedAt) {
}
