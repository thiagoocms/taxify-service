package br.com.cshub.taxify.dto.fiscalxml;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the "Notas fiscais" list - only what the table shows, read straight from
 * the fiscal_xml_documents columns (no XML file is opened to build it). The full note
 * is fetched on demand: header via {@code GET /{id}}, items via {@code GET /{id}/produtos}
 * and the raw file via {@code GET /{id}/xml}.
 */
public record FiscalXmlSummaryDTO(
        UUID id,
        String accessKey,
        String documentType,
        LocalDateTime issueDate,
        String modelo,
        String serie,
        String numero,
        String tipoOperacao,
        String naturezaOperacao,
        String emitenteNome,
        String issuerCnpj,
        String emitenteCpf,
        String destinatarioNome,
        String recipientCnpj,
        String destinatarioCpf,
        BigDecimal valorTotalNota,
        long quantidadeItens,
        boolean retificada
) {
}
