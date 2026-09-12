package br.com.cshub.taxify.dto.user_company;

import jakarta.validation.constraints.NotBlank;

public record UserCompanyDTO(

        @NotBlank
        String userId,
        @NotBlank
        String companyId,
        @NotBlank
        String role,
        Boolean deleted
) {
}
