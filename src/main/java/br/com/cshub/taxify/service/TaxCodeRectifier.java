package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom.first;

/**
 * Rewrites the tax <em>codes</em> of one NFe item ({@code det}) to what its {@link ProductTaxRule}
 * says - origin, CST/CSOSN, CEST, CST of IPI/PIS/COFINS, cClassTrib of IBS/CBS. Never a rate
 * or a value: changing those means recalculating the item and the note's totals, and a
 * wrong recalculation would make a worse XML than the original - the conferência points them out.
 *
 * <p>A code is only rewritten when the new one belongs to the same XML group as the current
 * one (ICMS40 holds CST 40, 41 and 50; PISNT holds 04-09...). A change that needs another
 * group (ICMS00 → ICMS60 has other fields) is left alone, and so is a field the note
 * doesn't have - nothing is inserted.
 */
final class TaxCodeRectifier {

    /** Group element → the codes it can hold. A group not listed holds only its own code (ICMS00 → 00). */
    private static final Map<String, Set<String>> ICMS_GROUPS = Map.of(
            "ICMS40", Set.of("40", "41", "50"),
            "ICMSSN102", Set.of("102", "103", "300", "400"),
            "ICMSSN202", Set.of("202", "203"));
    private static final Set<String> IPI_TRIB = Set.of("00", "49", "50", "99");
    private static final Set<String> IPI_NT = Set.of("01", "02", "03", "04", "05", "51", "52", "53", "54", "55");
    private static final Set<String> PIS_COFINS_ALIQ = Set.of("01", "02");
    private static final Set<String> PIS_COFINS_NT = Set.of("04", "05", "06", "07", "08", "09");
    private static final Predicate<String> PIS_COFINS_OUTR = code -> code.matches("\\d{2}") && Integer.parseInt(code) >= 49;

    private TaxCodeRectifier() {
    }

    static void apply(Element det, ProductTaxRule tax, String codigo, List<String> corrections) {
        Element prod = first(det, "prod");
        Element imposto = first(det, "imposto");
        set(prod, "CEST", tax.cest(), codigo, "CEST", corrections);
        if (imposto == null) {
            return;
        }

        Element icms = group(first(imposto, "ICMS"));
        if (icms != null) {
            set(icms, "orig", tax.origemMercadoria(), codigo, "orig", corrections);
            String name = icms.getLocalName();
            Set<String> allowed = ICMS_GROUPS.getOrDefault(name, Set.of());
            if (first(icms, "CSOSN") != null) {
                setIfAllowed(icms, "CSOSN", tax.csosn(), allowed::contains, codigo, "CSOSN", corrections);
            } else {
                setIfAllowed(icms, "CST", tax.cstIcms(), allowed::contains, codigo, "CST do ICMS", corrections);
            }
        }

        Element ipi = group(first(imposto, "IPI"));
        if (ipi != null) {
            Set<String> allowed = "IPITrib".equals(ipi.getLocalName()) ? IPI_TRIB
                    : "IPINT".equals(ipi.getLocalName()) ? IPI_NT : Set.of();
            setIfAllowed(ipi, "CST", tax.cstIpi(), allowed::contains, codigo, "CST do IPI", corrections);
        }

        pisCofins(group(first(imposto, "PIS")), "PIS", tax.cstPis(), codigo, corrections);
        pisCofins(group(first(imposto, "COFINS")), "COFINS", tax.cstCofins(), codigo, corrections);

        Element ibsCbs = first(imposto, "IBSCBS");
        String currentCst = text(first(ibsCbs, "CST"));
        String classTrib = tax.classificacaoTributaria();
        boolean sameCst = tax.cstIbsCbs() == null || Objects.equals(tax.cstIbsCbs(), currentCst);
        if (ibsCbs != null && classTrib != null && currentCst != null && sameCst && classTrib.startsWith(currentCst)) {
            set(ibsCbs, "cClassTrib", classTrib, codigo, "cClassTrib", corrections);
        }
    }

    private static void pisCofins(Element group, String tax, String newCst, String codigo, List<String> corrections) {
        if (group == null) {
            return;
        }
        String name = group.getLocalName();
        Predicate<String> allowed = (tax + "Aliq").equals(name) ? PIS_COFINS_ALIQ::contains
                : (tax + "NT").equals(name) ? PIS_COFINS_NT::contains
                : (tax + "Outr").equals(name) ? PIS_COFINS_OUTR
                : code -> false;
        setIfAllowed(group, "CST", newCst, allowed, codigo, "CST do " + tax, corrections);
    }

    private static void setIfAllowed(Element parent, String child, String newValue, Predicate<String> sameGroup,
                                     String codigo, String label, List<String> corrections) {
        Element field = first(parent, child);
        if (field == null || newValue == null || Objects.equals(text(field), newValue)) {
            return;
        }
        if (sameGroup.test(text(field)) && sameGroup.test(newValue)) {
            set(parent, child, newValue, codigo, label, corrections);
        }
    }

    private static void set(Element parent, String child, String newValue, String codigo, String label, List<String> corrections) {
        Element field = first(parent, child);
        if (field == null || newValue == null) {
            return;
        }
        String current = text(field);
        if (Objects.equals(current, newValue.trim())) {
            return;
        }
        corrections.add("Item %s: %s %s -> %s".formatted(codigo, label, current, newValue.trim()));
        field.setTextContent(newValue.trim());
    }

    /** The single element child of ICMS/IPI/PIS/COFINS that holds the fields (ICMS00, IPITrib...) - not plain fields like cEnq. */
    private static Element group(Element parent) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && hasElementChild(child)) {
                return child;
            }
        }
        return null;
    }

    private static boolean hasElementChild(Element element) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private static String text(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }
}
