package br.com.cshub.taxify.dto.capture;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resposta 202 do "Sincronizar agora" (CP-RN-15). {@code position} começa em 0 (0 = a próxima a ser atendida); a tela
 * mostra somando 1. {@code estimatedAt} é estimativa (horário de Recife, com o fuso).
 */
public record CapturePriorityResponseDTO(UUID requestId, String status, int position, OffsetDateTime estimatedAt) {
}
