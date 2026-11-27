package br.com.cshub.taxify.dto.password;

/** {@code POST /auth/password/forgot}: o login ou o e-mail (SN-RN-01). */
public record PasswordForgotRequestDTO(String identifier) {
}
