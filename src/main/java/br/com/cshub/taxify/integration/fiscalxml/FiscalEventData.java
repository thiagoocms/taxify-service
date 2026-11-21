package br.com.cshub.taxify.integration.fiscalxml;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * One SEFAZ event read from a procEventoNFe / procEventoCTe XML.
 *
 * @param type       tpEvento - 110111 cancelamento, 110112 cancelamento por substituição,
 *                   110110 carta de correção, 2102x0 manifestação do destinatário
 * @param text       justification (xJust) or correction text (xCorrecao), when the event has one
 * @param registered SEFAZ accepted it (cStat 135/136/155) - an unregistered event changes nothing
 */
public record FiscalEventData(
        String accessKey,
        String type,
        String description,
        Integer sequence,
        LocalDateTime eventAt,
        String protocol,
        String text,
        boolean registered
) {
    public static final String CANCELLATION = "110111";
    public static final String CANCELLATION_BY_SUBSTITUTION = "110112";
    public static final String CORRECTION_LETTER = "110110";
    private static final Set<String> CANCELLATIONS = Set.of(CANCELLATION, CANCELLATION_BY_SUBSTITUTION);

    public boolean isCancellation() {
        return registered && CANCELLATIONS.contains(type);
    }
}
