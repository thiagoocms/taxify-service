package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.domain.xml.FiscalIbsCbsTotals;
import br.com.cshub.taxify.domain.xml.FiscalItemIbsCbs;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEnderecoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlImpostoItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts fields directly from the fiscal XML content, so persistence and the API
 * response don't depend on SIEG's response envelope - only on the (public, stable)
 * NFe/CTe XML layout published by the Brazilian tax authority. For NFe this maps the
 * full header (ide/emit/dest/ICMSTot) and every line item (det/prod plus a summary of
 * its ICMS/IPI/PIS/COFINS and IBS/CBS); CTe only gets the basic identification fields for now,
 * since its layout (rem/exped/receb/infCTeNorm) is materially different from NFe's.
 */
@Component
public class FiscalXmlMetadataParser {

    /**
     * Bumped whenever the parser starts extracting fields it didn't before. Saved on each
     * note ({@code FiscalXmlDocument#metadataVersion}) so the backfill knows which stored
     * notes were parsed by an older version and need re-reading.
     * <ul>
     *   <li>1 - item tax detail (origin, CSOSN, ST, IPI/PIS/COFINS base and rate, CEST,
     *       cBenef) and IBS/CBS on items and totals.</li>
     *   <li>2 - IBS/CBS deferral/refund indicator (gDif/gDevTrib) on items.</li>
     *   <li>3 - IPI CST/value read from IPITrib/IPINT (before, cEnq was taken as the group
     *       and both were always empty).</li>
     * </ul>
     */
    public static final int CURRENT_VERSION = 3;

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlMetadataParser.class);
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ISO_LOCAL_DATE;

    public FiscalXmlMetadata parse(byte[] xmlBytes) {
        try {
            Document doc = FiscalXmlDomParser.parse(xmlBytes);

            Element infNFe = firstElementIgnoringNamespace(doc, "infNFe");
            if (infNFe != null) {
                return fromNfe(infNFe);
            }

            Element infCte = firstElementIgnoringNamespace(doc, "infCte");
            if (infCte != null) {
                return fromInfoElement(infCte, "CTE");
            }

            return empty();
        } catch (Exception e) {
            log.warn("Failed to parse metadata from a fiscal XML returned by SIEG", e);
            return empty();
        }
    }

    private FiscalXmlMetadata fromInfoElement(Element infoElement, String documentType) {
        String accessKey = normalizeAccessKey(infoElement.getAttribute("Id"));
        String issuerCnpj = textOf(firstElementIgnoringNamespace(infoElement, "emit"), "CNPJ");
        String recipientCnpj = textOf(firstElementIgnoringNamespace(infoElement, "dest"), "CNPJ");
        LocalDateTime issueDate = extractIssueDate(infoElement);
        return new FiscalXmlMetadata(accessKey, documentType, issuerCnpj, recipientCnpj, issueDate,
                null, null, null, null, List.of());
    }

    private FiscalXmlMetadata fromNfe(Element infNFe) {
        String accessKey = normalizeAccessKey(infNFe.getAttribute("Id"));
        Element ide = firstElementIgnoringNamespace(infNFe, "ide");
        Element emit = firstElementIgnoringNamespace(infNFe, "emit");
        Element dest = firstElementIgnoringNamespace(infNFe, "dest");
        Element total = firstElementIgnoringNamespace(infNFe, "total");
        Element icmsTot = total == null ? null : firstElementIgnoringNamespace(total, "ICMSTot");
        Element ibsCbsTot = total == null ? null : firstElementIgnoringNamespace(total, "IBSCBSTot");

        String issuerCnpj = textOf(emit, "CNPJ");
        String recipientCnpj = textOf(dest, "CNPJ");
        LocalDateTime issueDate = extractIssueDate(infNFe);

        return new FiscalXmlMetadata(accessKey, "NFE", issuerCnpj, recipientCnpj, issueDate,
                mapIdentificacao(ide), mapEmitente(emit), mapDestinatario(dest), mapTotais(icmsTot, ibsCbsTot), mapProdutos(infNFe));
    }

    private FiscalXmlIdentificacaoDTO mapIdentificacao(Element ide) {
        if (ide == null) {
            return null;
        }
        return new FiscalXmlIdentificacaoDTO(
                textOf(ide, "cUF"),
                textOf(ide, "natOp"),
                textOf(ide, "mod"),
                textOf(ide, "serie"),
                textOf(ide, "nNF"),
                parseDateTime(textOf(ide, "dhEmi")),
                parseDateTime(textOf(ide, "dhSaiEnt")),
                textOf(ide, "tpNF"),
                textOf(ide, "idDest"),
                textOf(ide, "cMunFG"),
                textOf(ide, "tpImp"),
                textOf(ide, "tpEmis"),
                textOf(ide, "cDV"),
                textOf(ide, "tpAmb"),
                textOf(ide, "finNFe"),
                textOf(ide, "indFinal"),
                textOf(ide, "indPres"),
                textOf(ide, "procEmi"),
                textOf(ide, "verProcEmi")
        );
    }

    private FiscalXmlEmitenteDTO mapEmitente(Element emit) {
        if (emit == null) {
            return null;
        }
        return new FiscalXmlEmitenteDTO(
                textOf(emit, "CNPJ"),
                textOf(emit, "CPF"),
                textOf(emit, "xNome"),
                textOf(emit, "xFant"),
                textOf(emit, "IE"),
                textOf(emit, "IEST"),
                textOf(emit, "IM"),
                textOf(emit, "CNAE"),
                textOf(emit, "CRT"),
                mapEndereco(firstElementIgnoringNamespace(emit, "enderEmit"))
        );
    }

    private FiscalXmlDestinatarioDTO mapDestinatario(Element dest) {
        if (dest == null) {
            return null;
        }
        return new FiscalXmlDestinatarioDTO(
                textOf(dest, "CNPJ"),
                textOf(dest, "CPF"),
                textOf(dest, "idEstrangeiro"),
                textOf(dest, "xNome"),
                textOf(dest, "IE"),
                textOf(dest, "indIEDest"),
                textOf(dest, "email"),
                mapEndereco(firstElementIgnoringNamespace(dest, "enderDest"))
        );
    }

    private FiscalXmlEnderecoDTO mapEndereco(Element ender) {
        if (ender == null) {
            return null;
        }
        return new FiscalXmlEnderecoDTO(
                textOf(ender, "xLgr"),
                textOf(ender, "nro"),
                textOf(ender, "xCpl"),
                textOf(ender, "xBairro"),
                textOf(ender, "cMun"),
                textOf(ender, "xMun"),
                textOf(ender, "UF"),
                textOf(ender, "CEP"),
                textOf(ender, "cPais"),
                textOf(ender, "xPais"),
                textOf(ender, "fone")
        );
    }

    private FiscalXmlTotaisDTO mapTotais(Element icmsTot, Element ibsCbsTot) {
        if (icmsTot == null && ibsCbsTot == null) {
            return null;
        }
        return new FiscalXmlTotaisDTO(
                decimalOf(icmsTot, "vBC"),
                decimalOf(icmsTot, "vICMS"),
                decimalOf(icmsTot, "vICMSDeson"),
                decimalOf(icmsTot, "vFCP"),
                decimalOf(icmsTot, "vBCST"),
                decimalOf(icmsTot, "vST"),
                decimalOf(icmsTot, "vFCPST"),
                decimalOf(icmsTot, "vProd"),
                decimalOf(icmsTot, "vFrete"),
                decimalOf(icmsTot, "vSeg"),
                decimalOf(icmsTot, "vDesc"),
                decimalOf(icmsTot, "vII"),
                decimalOf(icmsTot, "vIPI"),
                decimalOf(icmsTot, "vPIS"),
                decimalOf(icmsTot, "vCOFINS"),
                decimalOf(icmsTot, "vOutro"),
                decimalOf(icmsTot, "vNF"),
                decimalOf(icmsTot, "vTotTrib"),
                mapIbsCbsTotais(ibsCbsTot)
        );
    }

    /** total/IBSCBSTot - the IBS values sit in gIBS (gIBSUF/gIBSMun), the CBS one in gCBS. */
    private FiscalIbsCbsTotals mapIbsCbsTotais(Element ibsCbsTot) {
        if (ibsCbsTot == null) {
            return null;
        }
        Element gIbs = firstElementIgnoringNamespace(ibsCbsTot, "gIBS");
        Element gCbs = firstElementIgnoringNamespace(ibsCbsTot, "gCBS");
        return new FiscalIbsCbsTotals(
                decimalOf(ibsCbsTot, "vBCIBSCBS"),
                decimalOf(gIbs, "vIBSUF"),
                decimalOf(gIbs, "vIBSMun"),
                decimalOf(gIbs, "vIBS"),
                decimalOf(gCbs, "vCBS")
        );
    }

    private List<FiscalXmlProdutoDTO> mapProdutos(Element infNFe) {
        List<FiscalXmlProdutoDTO> produtos = new ArrayList<>();
        NodeList detNodes = infNFe.getElementsByTagNameNS("*", "det");
        for (int i = 0; i < detNodes.getLength(); i++) {
            if (!(detNodes.item(i) instanceof Element det)) {
                continue;
            }
            Element prod = firstElementIgnoringNamespace(det, "prod");
            if (prod == null) {
                continue;
            }
            produtos.add(new FiscalXmlProdutoDTO(
                    intOf(det.getAttribute("nItem")),
                    textOf(prod, "cProd"),
                    textOf(prod, "cEAN"),
                    textOf(prod, "xProd"),
                    textOf(prod, "NCM"),
                    textOf(prod, "CFOP"),
                    textOf(prod, "uCom"),
                    decimalOf(prod, "qCom"),
                    decimalOf(prod, "vUnCom"),
                    decimalOf(prod, "vProd"),
                    textOf(prod, "uTrib"),
                    decimalOf(prod, "qTrib"),
                    decimalOf(prod, "vUnTrib"),
                    mapImpostoItem(prod, firstElementIgnoringNamespace(det, "imposto"))
            ));
        }
        return produtos;
    }

    private FiscalXmlImpostoItemDTO mapImpostoItem(Element prod, Element imposto) {
        if (imposto == null) {
            return null;
        }
        Element icms = firstRegimeGroup(firstElementIgnoringNamespace(imposto, "ICMS"));
        Element ipi = firstRegimeGroup(firstElementIgnoringNamespace(imposto, "IPI"));
        Element pis = firstRegimeGroup(firstElementIgnoringNamespace(imposto, "PIS"));
        Element cofins = firstRegimeGroup(firstElementIgnoringNamespace(imposto, "COFINS"));

        return new FiscalXmlImpostoItemDTO(
                icmsSituacao(icms),
                decimalOf(icms, "vBC"),
                decimalOf(icms, "pICMS"),
                decimalOf(icms, "vICMS"),
                textOf(ipi, "CST"),
                decimalOf(ipi, "vIPI"),
                textOf(pis, "CST"),
                decimalOf(pis, "vPIS"),
                textOf(cofins, "CST"),
                decimalOf(cofins, "vCOFINS"),
                mapTaxDetail(prod, icms, ipi, pis, cofins),
                mapIbsCbs(firstElementIgnoringNamespace(imposto, "IBSCBS"))
        );
    }

    private FiscalItemTaxDetail mapTaxDetail(Element prod, Element icms, Element ipi, Element pis, Element cofins) {
        return new FiscalItemTaxDetail(
                textOf(prod, "CEST"),
                textOf(prod, "cBenef"),
                textOf(icms, "orig"),
                textOf(icms, "CSOSN"),
                textOf(icms, "modBC"),
                decimalOf(icms, "pRedBC"),
                decimalOf(icms, "vICMSDeson"),
                textOf(icms, "modBCST"),
                decimalOf(icms, "pMVAST"),
                decimalOf(icms, "vBCST"),
                decimalOf(icms, "pICMSST"),
                decimalOf(icms, "vICMSST"),
                decimalOf(ipi, "vBC"),
                decimalOf(ipi, "pIPI"),
                decimalOf(pis, "vBC"),
                decimalOf(pis, "pPIS"),
                decimalOf(cofins, "vBC"),
                decimalOf(cofins, "pCOFINS")
        );
    }

    /**
     * det/imposto/IBSCBS: CST and cClassTrib at the top, the values in gIBSCBS - one
     * subgroup per tax (gIBSUF, gIBSMun, gCBS), each with its rate, an optional reduction
     * (gRed/pRedAliq) and the value. A note with no IBSCBS group gets null.
     */
    private FiscalItemIbsCbs mapIbsCbs(Element ibsCbs) {
        if (ibsCbs == null) {
            return null;
        }
        Element values = firstElementIgnoringNamespace(ibsCbs, "gIBSCBS");
        Element ibsUf = firstElementIgnoringNamespace(values, "gIBSUF");
        Element ibsMun = firstElementIgnoringNamespace(values, "gIBSMun");
        Element cbs = firstElementIgnoringNamespace(values, "gCBS");
        return new FiscalItemIbsCbs(
                textOf(ibsCbs, "CST"),
                textOf(ibsCbs, "cClassTrib"),
                decimalOf(values, "vBC"),
                decimalOf(ibsUf, "pIBSUF"),
                decimalOf(firstElementIgnoringNamespace(ibsUf, "gRed"), "pRedAliq"),
                decimalOf(ibsUf, "vIBSUF"),
                decimalOf(ibsMun, "pIBSMun"),
                decimalOf(firstElementIgnoringNamespace(ibsMun, "gRed"), "pRedAliq"),
                decimalOf(ibsMun, "vIBSMun"),
                decimalOf(values, "vIBS"),
                decimalOf(cbs, "pCBS"),
                decimalOf(firstElementIgnoringNamespace(cbs, "gRed"), "pRedAliq"),
                decimalOf(cbs, "vCBS"),
                firstElementIgnoringNamespace(values, "gDif") != null
                        || firstElementIgnoringNamespace(values, "gDevTrib") != null
        );
    }

    private String icmsSituacao(Element icms) {
        String cst = textOf(icms, "CST");
        return cst != null ? cst : textOf(icms, "CSOSN");
    }

    /**
     * ICMS/IPI/PIS/COFINS wrap their real fields one level deeper, in a group element
     * whose name varies by tax regime (ICMS00, ICMS60, ICMSSN101, IPITrib, PISAliq,
     * PISOutr...) - NFe defines dozens of these. Rather than enumerate every variant,
     * just take the child element that is a group. Not simply the first child: IPI starts
     * with plain fields (cEnq, CNPJProd, cSelo...) before IPITrib/IPINT.
     */
    private Element firstRegimeGroup(Element parent) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && hasChildElement(child)) {
                return child;
            }
        }
        return null;
    }

    private boolean hasChildElement(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private Integer intOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimalOf(Element parent, String childLocalName) {
        String value = textOf(parent, childLocalName);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDate.parse(value, DATE_ONLY).atStartOfDay();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private FiscalXmlMetadata empty() {
        return new FiscalXmlMetadata(null, "UNKNOWN", null, null, null, null, null, null, null, List.of());
    }

    private String normalizeAccessKey(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String digitsOnly = rawId.replaceAll("\\D", "");
        return digitsOnly.length() == 44 ? digitsOnly : null;
    }

    private LocalDateTime extractIssueDate(Element infoElement) {
        Element ide = firstElementIgnoringNamespace(infoElement, "ide");
        if (ide == null) {
            return null;
        }
        LocalDateTime dhEmi = parseDateTime(textOf(ide, "dhEmi"));
        if (dhEmi != null) {
            return dhEmi;
        }
        return parseDateTime(textOf(ide, "dEmi"));
    }

    private String textOf(Element parent, String childLocalName) {
        Element child = firstElementIgnoringNamespace(parent, childLocalName);
        return child == null ? null : child.getTextContent();
    }

    private Element firstElementIgnoringNamespace(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private Element firstElementIgnoringNamespace(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }
}
