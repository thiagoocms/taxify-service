package br.com.cshub.taxify.dto.company;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The company's entries SEFAZ handed over only as a summary (waiting for the full XML), how
 * the Ciência da Operação is going for them, and the SIEG x SEFAZ comparison.
 *
 * @param autoCiencia       Taxify sends the ciência by itself: the plan has the manifestation service
 *                          (and the server hasn't turned it off - sefaz.dist.auto-ciencia)
 * @param aguardandoCiencia summaries the ciência wasn't sent for yet
 * @param aguardandoXml     ciência registered, full XML not here yet
 * @param comErro           ciência refused by SEFAZ (retried a few times, then waits for "reenviar")
 * @param soSieg            third-party notes SIEG has had for 48h+ that SEFAZ never handed over - null
 *                          when the plan doesn't have both sources
 * @param soSefaz           notes SEFAZ handed over 48h+ ago that SIEG never brought - null likewise
 * @param notas             the summaries still waiting, oldest first (up to 500)
 */
public record SefazNotesDTO(
        boolean autoCiencia,
        long aguardandoCiencia,
        long aguardandoXml,
        long comErro,
        Long soSieg,
        Long soSefaz,
        List<Summary> notas
) {

    /** One note SEFAZ gave only as a summary. */
    public record Summary(
            String chave,
            String emitenteDocumento,
            String emitenteNome,
            LocalDateTime dataEmissao,
            BigDecimal valorTotal,
            /** cSitNFe: 1 autorizada, 2 denegada, 3 cancelada. */
            String situacao,
            /** PENDENTE, ENVIADA or ERRO. */
            String ciencia,
            LocalDateTime cienciaEm,
            String cienciaDetalhe,
            int cienciaTentativas,
            LocalDateTime ultimaBuscaEm,
            String buscaDetalhe,
            LocalDateTime recebidoEm
    ) {
    }
}
