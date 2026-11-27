package br.com.cshub.taxify.dto.password;

/** {@code POST /auth/password/reset/check}: o token do fragmento do link (SN-RN-05). */
public record PasswordResetCheckRequestDTO(String token) {
}
