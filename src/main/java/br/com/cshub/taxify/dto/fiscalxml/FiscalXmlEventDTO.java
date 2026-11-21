package br.com.cshub.taxify.dto.fiscalxml;

import java.time.LocalDateTime;

/** One SEFAZ event of a note, as the detail screen shows it. */
public record FiscalXmlEventDTO(
        String type,
        String description,
        int sequence,
        LocalDateTime eventAt,
        String protocol,
        String text,
        boolean registered
) {
}
