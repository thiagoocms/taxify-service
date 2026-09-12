package br.com.cshub.taxify.dto.auth;

public record AuthenticatedDTO(
        String token,
        String userId,
        String userName
) {
}
