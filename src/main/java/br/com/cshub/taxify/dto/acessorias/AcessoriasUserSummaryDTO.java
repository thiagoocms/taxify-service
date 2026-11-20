package br.com.cshub.taxify.dto.acessorias;

/**
 * A user of the firm's Acessórias account, matched by e-mail against the firm's Taxify users.
 *
 * @param taxifyUserId the Taxify user with this e-mail in the same firm; null when there's none yet
 */
public record AcessoriasUserSummaryDTO(
        String acessoriasId,
        String name,
        String email,
        boolean active,
        String taxifyUserId
) {
}
