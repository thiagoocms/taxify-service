package br.com.cshub.taxify.integration.acessorias;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One user of the firm's Acessórias account as {@code GET /users/ListAll} returns it -
 * {@code status} is "Ativo" or "Inativo".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcessoriasUserDTO(
        String id,
        String nome,
        String email,
        String status
) {
}
