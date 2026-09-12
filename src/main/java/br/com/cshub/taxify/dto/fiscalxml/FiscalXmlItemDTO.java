package br.com.cshub.taxify.dto.fiscalxml;

import java.time.LocalDateTime;
import java.util.List;

public record FiscalXmlItemDTO(
        String accessKey,
        String documentType,
        String issuerCnpj,
        String recipientCnpj,
        LocalDateTime issueDate,
        FiscalXmlIdentificacaoDTO identificacao,
        FiscalXmlEmitenteDTO emitente,
        FiscalXmlDestinatarioDTO destinatario,
        FiscalXmlTotaisDTO totais,
        List<FiscalXmlProdutoDTO> produtos,
        String xmlContent
) {
}
