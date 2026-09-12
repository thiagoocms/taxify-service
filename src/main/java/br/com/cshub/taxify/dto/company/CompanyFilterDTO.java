package br.com.cshub.taxify.dto.company;

import jakarta.validation.constraints.NotBlank;


public record CompanyFilterDTO(
        String id,
        String documentNumber,
        String documentType,
        String name,
        Boolean deleted
) {

}
