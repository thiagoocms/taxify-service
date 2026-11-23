package br.com.cshub.taxify.integration.fiscalxml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;

import static br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom.first;
import static br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom.text;

/**
 * Recognizes SEFAZ event XMLs (procEventoNFe, procEventoCTe, or a bare evento) - what SIEG
 * returns next to the notes when asked with BaixarEventos. Anything else is not an event:
 * {@link #parse} returns empty and the caller treats the XML as a note.
 */
@Component
public class FiscalEventParser {

    private static final Logger log = LoggerFactory.getLogger(FiscalEventParser.class);
    /** 135 registered, 136 registered without linking to the note, 155 cancellation registered after the deadline. */
    private static final Set<String> REGISTERED = Set.of("135", "136", "155");

    public Optional<FiscalEventData> parse(byte[] xmlBytes) {
        Document doc;
        try {
            doc = FiscalXmlDomParser.parse(xmlBytes);
        } catch (Exception e) {
            return Optional.empty();
        }
        Element root = doc.getDocumentElement();
        String rootName = root == null ? "" : root.getLocalName() == null ? root.getNodeName() : root.getLocalName();
        if (!rootName.startsWith("procEvento") && !rootName.equals("evento")) {
            return Optional.empty();
        }

        Element evento = first(doc.getDocumentElement(), "evento");
        Element infEvento = first(evento != null ? evento : root, "infEvento");
        if (infEvento == null) {
            log.warn("Event XML without infEvento - ignored");
            return Optional.empty();
        }
        String accessKey = firstText(infEvento, "chNFe", "chCTe");
        if (!StringUtils.hasText(accessKey)) {
            return Optional.empty();
        }
        Element detEvento = first(infEvento, "detEvento");
        Element retInfEvento = first(first(root, "retEvento"), "infEvento");
        String status = retInfEvento == null ? null : text(retInfEvento, "cStat");

        return Optional.of(new FiscalEventData(
                accessKey.strip(),
                text(infEvento, "tpEvento"),
                firstText(detEvento, "descEvento"),
                parseInt(text(infEvento, "nSeqEvento")),
                parseDateTime(text(infEvento, "dhEvento")),
                retInfEvento == null ? null : text(retInfEvento, "nProt"),
                firstText(detEvento, "xJust", "xCorrecao"),
                // no retEvento (a bare evento) = can't tell it was accepted - treat as not registered
                status != null && REGISTERED.contains(status.strip())));
    }

    private static String firstText(Element parent, String... localNames) {
        for (String name : localNames) {
            String value = text(parent, name);
            if (StringUtils.hasText(value)) {
                return value.strip();
            }
        }
        return null;
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "2026-11-20T10:15:30-03:00" - kept as the local date-time SEFAZ reported. */
    private static LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.strip()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value.strip());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
