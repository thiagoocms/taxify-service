package br.com.cshub.taxify.dto.user;

public record UserFilterDTO(
        String id,

        String name,
        String documentNumber,
        String documentType,
        String login,
        String password,
        String email,
        String profile,
        Boolean deleted
) {
}
