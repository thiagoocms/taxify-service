package br.com.cshub.taxify.dto.fiscalxml;

public record FiscalXmlEmitenteDTO(
        String cnpj,
        String cpf,
        String nome,
        String nomeFantasia,
        String inscricaoEstadual,
        String inscricaoEstadualSubstitutoTributario,
        String inscricaoMunicipal,
        String cnae,
        String regimeTributario,
        FiscalXmlEnderecoDTO endereco
) {
}
