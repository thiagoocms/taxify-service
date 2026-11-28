package br.com.cshub.taxify.integration.fiscalxml;

import br.com.cshub.taxify.domain.fiscaldocument.Withholdings;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;

/**
 * The federal retentions of an NF-e (total/retTrib): vRetPIS, vRetCOFINS, vRetCSLL, vIRRF and
 * vRetPrev (INSS). Rare on goods notes - they show up when the note also bills a service.
 */
public record NfeRetentions(BigDecimal pis, BigDecimal cofins, BigDecimal csll, BigDecimal irrf, BigDecimal inss) {

    public static final NfeRetentions NONE = new NfeRetentions(null, null, null, null, null);

    public static NfeRetentions parse(byte[] xml) {
        try {
            return of(FiscalXmlDomParser.parse(xml));
        } catch (Exception e) {
            return NONE;
        }
    }

    public static NfeRetentions of(Document doc) {
        Element retTrib = FiscalXmlDom.first(FiscalXmlDom.first(doc, "total"), "retTrib");
        if (retTrib == null) {
            return NONE;
        }
        return new NfeRetentions(decimal(retTrib, "vRetPIS"), decimal(retTrib, "vRetCOFINS"), decimal(retTrib, "vRetCSLL"),
                decimal(retTrib, "vIRRF"), decimal(retTrib, "vRetPrev"));
    }

    /** The retentions as the document keeps them ({@code withheld_*_amount}). */
    public Withholdings toWithholdings() {
        return new Withholdings(pis, cofins, csll, irrf, inss);
    }

    private static BigDecimal decimal(Element parent, String name) {
        String text = FiscalXmlDom.text(parent, name);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(text.strip());
            return value.signum() == 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
