package br.com.cshub.taxify.dto.company;

import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.TaxRegime;
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
        /** Label only - the structured fields below are what counts; sent alone (imports), it is parsed. */
        String taxRegime,
        TaxRegime taxRegimeType,
        BusinessActivity businessActivity,
        SimplesAnnex simplesServiceAnnex,
        /** "IE/UF; IE/UF" - parsed into the company's state registrations on save. */
        String stateRegistration,
        LocalDate openingDate,
        String email,
        String municipalRegistration,
        String mainCnae,
        String secondaryCnaes,
        String addressStreet,
        String addressNumber,
        String addressComplement,
        String addressDistrict,
        String addressCityCode,
        String addressCity,
        String addressZipCode,
        /** Write-only: month ("2026-01") a regime change starts; empty = the current month. */
        String taxRegimeValidFrom,
        String companyGroup,
        Boolean active,
        LocalDate clientSince,
        LocalDate clientUntil,
        // Read-only: set by the Acessórias import, ignored on create/update.
        String acessoriasId,
        Boolean deleted
) {

}
