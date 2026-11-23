package br.com.cshub.taxify.dto.fiscalxml;

import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.enumerated.FiscalDirection;
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
        String issuerCnpj,
        String recipientCnpj,
        LocalDateTime issueDate,
        boolean retificada,
        long quantidadeItens,
        FiscalXmlIdentificacaoDTO identificacao,
        FiscalXmlEmitenteDTO emitente,
        FiscalXmlDestinatarioDTO destinatario,
        FiscalXmlTotaisDTO totais,
        FiscalDirection direction,
        boolean ownIssue,
        FiscalDocumentStatus status,
        LocalDateTime canceledAt,
        ReviewStatus reviewStatus,
        LocalDateTime reviewedAt,
        String reviewedBy,
        String reviewNote,
        /** SIEG, SEFAZ_DIST or UPLOAD - where the note came from first. */
        String origem,
        /** When SIEG / SEFAZ had the note - null = that source never brought it (or before this was tracked). */
        LocalDateTime encontradaSiegEm,
        LocalDateTime encontradaSefazEm
) {

    /** Without the source fields - callers that don't know them. */
    public FiscalXmlDetailDTO(UUID id, UUID companyId, String accessKey, String documentType, String issuerCnpj,
                              String recipientCnpj, LocalDateTime issueDate, boolean retificada, long quantidadeItens,
                              FiscalXmlIdentificacaoDTO identificacao, FiscalXmlEmitenteDTO emitente,
                              FiscalXmlDestinatarioDTO destinatario, FiscalXmlTotaisDTO totais, FiscalDirection direction,
                              boolean ownIssue, FiscalDocumentStatus status, LocalDateTime canceledAt, ReviewStatus reviewStatus,
                              LocalDateTime reviewedAt, String reviewedBy, String reviewNote) {
        this(id, companyId, accessKey, documentType, issuerCnpj, recipientCnpj, issueDate, retificada, quantidadeItens,
                identificacao, emitente, destinatario, totais, direction, ownIssue, status, canceledAt, reviewStatus,
                reviewedAt, reviewedBy, reviewNote, null, null, null);
    }
}
