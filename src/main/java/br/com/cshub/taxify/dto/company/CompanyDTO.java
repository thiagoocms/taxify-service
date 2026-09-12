package br.com.cshub.taxify.dto.company;

import jakarta.validation.constraints.NotBlank;


public record CompanyDTO(
        String id,
        @NotBlank
        String documentNumber,
        @NotBlank
        String documentType,
        @NotBlank
        String name,
        Boolean deleted
) {

}
