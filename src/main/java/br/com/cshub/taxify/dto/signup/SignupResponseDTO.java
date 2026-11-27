package br.com.cshub.taxify.dto.signup;

/** 201: cadastro criado, aguardando o clique no link enviado ao e-mail do ADMIN. */
public record SignupResponseDTO(String status, int verificationHours) {
}
