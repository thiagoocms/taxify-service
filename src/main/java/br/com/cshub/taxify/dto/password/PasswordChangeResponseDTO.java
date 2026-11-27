package br.com.cshub.taxify.dto.password;

/** O JWT novo da sessão atual, com a versão de sessão nova (SN-RN-09, SN-RN-11): o front troca o token salvo. */
public record PasswordChangeResponseDTO(String token) {
}
