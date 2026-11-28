package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the parser read of one fiscal XML (NF-e/NFC-e in full, CT-e in part). {@code cte}: the participant groups of a
 * CT-e (null for any other document).
 */
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
        List<FiscalXmlProdutoDTO> produtos,
        CteParticipants cte
) {

    /** Document types the parser tells apart. */
    public static final String NFE = "NFE";
    public static final String CTE = "CTE";
    public static final String UNKNOWN = "UNKNOWN";

    /** Without the CT-e groups (NF-e, unknown documents and tests). */
    public FiscalXmlMetadata(String accessKey, String documentType, String issuerCnpj, String recipientCnpj,
                             LocalDateTime issueDate, FiscalXmlIdentificacaoDTO identificacao, FiscalXmlEmitenteDTO emitente,
                             FiscalXmlDestinatarioDTO destinatario, FiscalXmlTotaisDTO totais, List<FiscalXmlProdutoDTO> produtos) {
        this(accessKey, documentType, issuerCnpj, recipientCnpj, issueDate, identificacao, emitente, destinatario, totais,
                produtos, null);
    }
}
