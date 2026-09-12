package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;

import java.time.LocalDateTime;
import java.util.List;

public record FiscalXmlMetadata(
        String accessKey,
        String documentType,
        String issuerCnpj,
        String recipientCnpj,
        LocalDateTime issueDate,
        FiscalXmlIdentificacaoDTO identificacao,
        FiscalXmlEmitenteDTO emitente,
        FiscalXmlDestinatarioDTO destinatario,
        FiscalXmlTotaisDTO totais,
        List<FiscalXmlProdutoDTO> produtos
) {
}
