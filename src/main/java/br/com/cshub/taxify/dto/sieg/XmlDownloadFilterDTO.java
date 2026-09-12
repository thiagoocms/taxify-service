package br.com.cshub.taxify.dto.sieg;

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
        String cnpjDest
) {
}
