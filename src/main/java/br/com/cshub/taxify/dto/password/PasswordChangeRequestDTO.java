package br.com.cshub.taxify.dto.password;

/** {@code PUT /users/me/password} (SN-RN-09). */
public record PasswordChangeRequestDTO(String currentPassword, String newPassword) {
}
