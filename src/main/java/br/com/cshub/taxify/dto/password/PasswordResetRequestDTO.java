package br.com.cshub.taxify.dto.password;

/** {@code POST /auth/password/reset} (SN-RN-06). */
public record PasswordResetRequestDTO(String token, String newPassword) {
}
