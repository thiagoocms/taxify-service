package br.com.cshub.taxify.dto.company;

import jakarta.validation.constraints.NotBlank;


public record CompanyFilterDTO(
        String id,
        String documentNumber,
        String documentType,
        String name,
        Boolean deleted,
        /** Free search: part of the name OR of the CNPJ/CPF (with or without punctuation). */
        String search,
        /** Only the companies of this UF ("PE") - the Fronteira PE search. */
        String uf
) {

}
