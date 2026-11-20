package br.com.cshub.taxify.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UserDTO(
        String id,
        @NotBlank
        String name,
        @NotBlank
        String documentNumber,
        @NotBlank
        String documentType,
        @NotBlank
        String login,
        // Write-only: required on create (see UserValidation), optional on update (blank keeps
        // the current one) and never returned by the API.
        String password,
        @NotBlank
        String email,
        @NotNull
        String profile,
        Boolean deleted
) {
}
