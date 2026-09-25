package br.com.cshub.taxify.dto.fiscalxml;

import br.com.cshub.taxify.dto.PageRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * Body of the stored-XML search: company/period filter plus the page to return. Kept
 * apart from the SIEG XmlDownloadFilterDTO, whose take/skip/xmlType belong to the SIEG
 * API call, not to our own database pagination.
 */
public record XmlSearchFilterDTO(
        @NotBlank
        String companyId,
        LocalDate dataEmissaoInicio,
        LocalDate dataEmissaoFim,
        @Valid
        PageRequestDTO pagination
) {
}
