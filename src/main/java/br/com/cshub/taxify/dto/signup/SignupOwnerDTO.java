package br.com.cshub.taxify.dto.signup;

/** O ADMIN do escritório: CPF em {@code document}. */
public record SignupOwnerDTO(String name, String document, String login, String email, String password) {
}
