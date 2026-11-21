package br.com.cshub.taxify.dto.sieg;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record XmlDownloadFilterDTO(
        @NotBlank
        String companyId,
        @NotNull
        Integer xmlType,
        LocalDate dataEmissaoInicio,
        LocalDate dataEmissaoFim,
        Integer take,
        Integer skip,
        String cnpjDest,
        /** RECEIVED (supplier notes) or ISSUED (the company's own); null = both, one after the other. */
        FiscalXmlSide side
) {
}
