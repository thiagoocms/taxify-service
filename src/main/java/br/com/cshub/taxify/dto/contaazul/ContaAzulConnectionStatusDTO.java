package br.com.cshub.taxify.dto.contaazul;

import java.time.LocalDateTime;

/**
 * @param configured the Taxify app credentials (conta-azul.*) are set on the server
 * @param connected  this firm has authorized Taxify in its Conta Azul account
 */
public record ContaAzulConnectionStatusDTO(boolean configured, boolean connected, LocalDateTime connectedAt) {
}
