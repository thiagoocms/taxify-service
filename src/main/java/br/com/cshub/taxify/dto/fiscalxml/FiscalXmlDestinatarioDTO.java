package br.com.cshub.taxify.dto.fiscalxml;

public record FiscalXmlDestinatarioDTO(
        String cnpj,
        String cpf,
        String idEstrangeiro,
        String nome,
        String inscricaoEstadual,
        String indicadorIe,
        String email,
        FiscalXmlEnderecoDTO endereco
) {
}
