package br.com.cshub.taxify.dto.fiscalxml;

import br.com.cshub.taxify.enumerated.FiscalXmlDirection;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Header of a single note for the detail page - identification, parties and totals.
 * Items and the raw XML are left out on purpose; they have their own endpoints so the
 * page only pays for them when the matching tab is opened.
 */
public record FiscalXmlDetailDTO(
        UUID id,
        UUID companyId,
        String accessKey,
        String documentType,
        FiscalXmlDirection direcao,
        String issuerCnpj,
        String recipientCnpj,
        LocalDateTime issueDate,
        boolean retificada,
        long quantidadeItens,
        FiscalXmlIdentificacaoDTO identificacao,
        FiscalXmlEmitenteDTO emitente,
        FiscalXmlDestinatarioDTO destinatario,
        FiscalXmlTotaisDTO totais
) {
}
