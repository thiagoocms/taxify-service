package br.com.cshub.taxify.dto.company;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;


public record CompanyDTO(
        String id,
        @NotBlank
        String documentNumber,
        @NotBlank
        String documentType,
        @NotBlank
        String name,
        String tradeName,
        String phone,
        String uf,
        String taxRegime,
        String stateRegistration,
        String companyGroup,
        Boolean active,
        LocalDate clientSince,
        LocalDate clientUntil,
        // Read-only: set by the Acessórias import, ignored on create/update.
        String acessoriasId,
        Boolean deleted
) {

}
