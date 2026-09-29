package br.com.cshub.taxify.dto.sieg;

import br.com.cshub.taxify.enumerated.FiscalXmlDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * {@code direcao}: which side of the note the company is on - decides whether the
 * company's CNPJ goes to SIEG as CNPJdest (ENTRADA) or CNPJemit (SAIDA). Null = ENTRADA,
 * the only behavior that existed before. Replaces the old {@code cnpjDest}, which was
 * never read (the company's own CNPJ was always used).
 */
public record XmlDownloadFilterDTO(
        @NotBlank
        String companyId,
        @NotNull
        Integer xmlType,
        LocalDate dataEmissaoInicio,
        LocalDate dataEmissaoFim,
        Integer take,
        Integer skip,
        FiscalXmlDirection direcao
) {
    public FiscalXmlDirection direcaoOrDefault() {
        return direcao != null ? direcao : FiscalXmlDirection.ENTRADA;
    }
}
