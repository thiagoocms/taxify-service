package br.com.cshub.taxify.dto.fiscalxml;

public record FiscalXmlEnderecoDTO(
        String logradouro,
        String numero,
        String complemento,
        String bairro,
        String codigoMunicipio,
        String municipio,
        String uf,
        String cep,
        String codigoPais,
        String pais,
        String telefone
) {
}
