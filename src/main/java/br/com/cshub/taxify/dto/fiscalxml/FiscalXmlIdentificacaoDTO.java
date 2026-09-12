package br.com.cshub.taxify.dto.fiscalxml;

import java.time.LocalDateTime;

public record FiscalXmlIdentificacaoDTO(
        String codigoUf,
        String naturezaOperacao,
        String modelo,
        String serie,
        String numero,
        LocalDateTime dataEmissao,
        LocalDateTime dataSaidaEntrada,
        String tipoOperacao,
        String destinoOperacao,
        String codigoMunicipioFatoGerador,
        String tipoImpressao,
        String tipoEmissao,
        String digitoVerificador,
        String ambiente,
        String finalidadeEmissao,
        String consumidorFinal,
        String indicadorPresenca,
        String processoEmissao,
        String versaoProcessoEmissao
) {
}
