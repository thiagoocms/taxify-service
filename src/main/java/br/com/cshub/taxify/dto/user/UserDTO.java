package br.com.cshub.taxify.dto.user;

import br.com.cshub.taxify.enumerated.AccessProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

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
        /** Fixed access profiles (FISCAL, FINANCIAL...); only an ADMIN can set them, ignored for ADMIN users. */
        Set<AccessProfile> accessProfiles,
        Boolean deleted
) {
}
