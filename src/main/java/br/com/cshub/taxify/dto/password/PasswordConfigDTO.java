package br.com.cshub.taxify.dto.password;

/**
 * {@code GET /auth/password/config} (SN-RN-14): com {@code available=false} a tela de login mantém "Peça ao
 * administrador" e não mostra o formulário; {@code minLength} alimenta a validação do front.
 */
public record PasswordConfigDTO(boolean available, int resetMinutes, int minLength) {
}
