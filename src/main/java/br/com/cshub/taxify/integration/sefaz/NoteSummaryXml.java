package br.com.cshub.taxify.integration.sefaz;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * What a resNFe (the summary SEFAZ distributes to the recipient before the Ciência) carries:
 * key, issuer, issue date, total, situation and authorization - no items.
 *
 * @param operationType tpNF: 0 entrada, 1 saída (issuer's view)
 * @param situation     cSitNFe: 1 autorizada, 2 denegada, 3 cancelada
 */
public record NoteSummaryXml(
        String accessKey,
        String issuerDocument,
        String issuerName,
        String issuerIe,
        LocalDateTime issueDate,
        String operationType,
        BigDecimal totalValue,
        String situation,
        LocalDateTime authorizedAt,
        String protocol
) {

    /** Empty when the XML isn't a resNFe with a 44-digit key. */
    public static Optional<NoteSummaryXml> parse(byte[] xml) {
        Document doc;
        try {
            doc = FiscalXmlDomParser.parse(xml);
        } catch (Exception e) {
            return Optional.empty();
        }
        Element root = doc.getDocumentElement();
        String name = root == null ? "" : root.getLocalName() == null ? root.getNodeName() : root.getLocalName();
        if (!"resNFe".equals(name)) {
            return Optional.empty();
        }
        String key = strip(FiscalXmlDom.text(root, "chNFe"));
        if (key == null || !key.matches("\\d{44}")) {
            return Optional.empty();
        }
        String document = strip(FiscalXmlDom.text(root, "CNPJ"));
        if (document == null) {
            document = strip(FiscalXmlDom.text(root, "CPF"));
        }
        return Optional.of(new NoteSummaryXml(
                key,
                document,
                truncate(strip(FiscalXmlDom.text(root, "xNome")), 150),
                truncate(strip(FiscalXmlDom.text(root, "IE")), 20),
                dateTime(FiscalXmlDom.text(root, "dhEmi")),
                strip(FiscalXmlDom.text(root, "tpNF")),
                decimal(FiscalXmlDom.text(root, "vNF")),
                strip(FiscalXmlDom.text(root, "cSitNFe")),
                dateTime(FiscalXmlDom.text(root, "dhRecbto")),
                truncate(strip(FiscalXmlDom.text(root, "nProt")), 20)));
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static BigDecimal decimal(String value) {
        try {
            return value == null || value.isBlank() ? null : new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "2026-11-20T10:15:30-03:00" - kept as the local date-time SEFAZ reported, like the notes. */
    private static LocalDateTime dateTime(String value) {
        if (value == null || value.isBlank()) {
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
