package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.dto.fiscalxml.FiscalIbsCbsTotalsDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalItemIbsCbsDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalItemTaxDetailDTO;
import br.com.cshub.taxify.enumerated.CteTakerRole;
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

import static br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom.first;
import static br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom.text;

/**
 * Extracts fields directly from the fiscal XML content, so persistence and the API
 * response don't depend on SIEG's response envelope - only on the (public, stable)
 * NFe/CTe XML layout published by the Brazilian tax authority. For NFe this maps the
 * full header (ide/emit/dest/ICMSTot) and every line item (det/prod plus a summary of
 * its ICMS/IPI/PIS/COFINS and IBS/CBS); CTe only gets the basic identification fields and the document of each
 * participant group (emit/rem/exped/receb/dest/toma - the company's roles, FD-RN-08), since its layout is
 * materially different from NFe's.
 */
@Component
public class FiscalXmlMetadataParser {

    /**
     * Bumped whenever the parser starts extracting fields it didn't before. Saved on each
     * note ({@code FiscalDocument#metadataVersion}) so the backfill knows which stored
     * notes were parsed by an older version and need re-reading.
     * <ul>
     *   <li>1 - item tax detail (origin, CSOSN, ST, IPI/PIS/COFINS base and rate, CEST,
     *       cBenef) and IBS/CBS on items and totals.</li>
     *   <li>2 - IBS/CBS deferral/refund indicator (gDif/gDevTrib) on items.</li>
     *   <li>3 - IPI CST/value read from IPITrib/IPINT (before, cEnq was taken as the group
     *       and both were always empty).</li>
     *   <li>4 - header fields only the detail screen shows (the {@code header_*} and country
     *       columns), so opening a note no longer reads its file.</li>
     * </ul>
     */
    /**
     * 5: the federal retentions (retTrib) are stored too - see NfeRetentions.
     * 6: the credit a Simples Nacional supplier passes on (pCredSN / vCredICMSSN) - the Fronteira PE uses it.
     */
    public static final int CURRENT_VERSION = 6;

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlMetadataParser.class);
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ISO_LOCAL_DATE;

    public FiscalXmlMetadata parse(byte[] xmlBytes) {
        try {
            Document doc = FiscalXmlDomParser.parse(xmlBytes);

            Element infNFe = first(doc, "infNFe");
            if (infNFe != null) {
                return fromNfe(infNFe);
            }

            Element infCte = first(doc, "infCte");
            if (infCte != null) {
                return fromCte(infCte);
            }

            return empty();
        } catch (Exception e) {
            log.warn("Failed to parse metadata from a fiscal XML returned by SIEG", e);
            return empty();
        }
    }

    private FiscalXmlMetadata fromCte(Element infCte) {
        String accessKey = normalizeAccessKey(infCte.getAttribute("Id"));
        String issuerCnpj = text(first(infCte, "emit"), "CNPJ");
        String recipientCnpj = text(first(infCte, "dest"), "CNPJ");
        LocalDateTime issueDate = extractIssueDate(infCte);
        return new FiscalXmlMetadata(accessKey, FiscalXmlMetadata.CTE, issuerCnpj, recipientCnpj, issueDate,
                null, null, null, null, List.of(), mapCteParticipants(infCte));
    }

    /**
     * The six participant groups of a CT-e. The taker: {@code ide/toma3/toma} points at one of rem (0), exped (1),
     * receb (2) or dest (3); {@code ide/toma4} (toma = 4) brings its own document.
     */
    private CteParticipants mapCteParticipants(Element infCte) {
        CteParticipants.Party issuer = cteParty(first(infCte, "emit"), "enderEmit");
        CteParticipants.Party sender = cteParty(first(infCte, "rem"), "enderReme");
        CteParticipants.Party shipper = cteParty(first(infCte, "exped"), "enderExped");
        CteParticipants.Party receiver = cteParty(first(infCte, "receb"), "enderReceb");
        CteParticipants.Party recipient = cteParty(first(infCte, "dest"), "enderDest");
        Element ide = first(infCte, "ide");
        Element toma3 = ide == null ? null : first(ide, "toma3");
        Element toma4 = ide == null ? null : first(ide, "toma4");
        CteTakerRole takerRole = null;
        CteParticipants.Party taker = null;
        if (toma4 != null) {
            takerRole = CteTakerRole.OTHER;
            taker = cteParty(toma4, "enderToma");
        } else if (toma3 != null) {
            String code = text(toma3, "toma");
            takerRole = code == null ? null : switch (code.trim()) {
                case "0" -> CteTakerRole.SENDER;
                case "1" -> CteTakerRole.SHIPPER;
                case "2" -> CteTakerRole.RECEIVER;
                case "3" -> CteTakerRole.RECIPIENT;
                default -> null;
            };
            if (takerRole != null) {
                taker = switch (takerRole) {
                    case SENDER -> sender;
                    case SHIPPER -> shipper;
                    case RECEIVER -> receiver;
                    case RECIPIENT -> recipient;
                    case OTHER -> null;
                };
            }
        }
        return new CteParticipants(issuer, sender, shipper, receiver, recipient, taker, takerRole);
    }

    private CteParticipants.Party cteParty(Element group, String addressTag) {
        if (group == null) {
            return null;
        }
        Element address = first(group, addressTag);
        String name = text(group, "xNome");
        return new CteParticipants.Party(text(group, "CNPJ"), text(group, "CPF"), name, text(address, "UF"));
    }

    private FiscalXmlMetadata fromNfe(Element infNFe) {
        String accessKey = normalizeAccessKey(infNFe.getAttribute("Id"));
        Element ide = first(infNFe, "ide");
        Element emit = first(infNFe, "emit");
        Element dest = first(infNFe, "dest");
        Element total = first(infNFe, "total");
        Element icmsTot = total == null ? null : first(total, "ICMSTot");
        Element ibsCbsTot = total == null ? null : first(total, "IBSCBSTot");

        String issuerCnpj = text(emit, "CNPJ");
        String recipientCnpj = text(dest, "CNPJ");
        LocalDateTime issueDate = extractIssueDate(infNFe);

        return new FiscalXmlMetadata(accessKey, FiscalXmlMetadata.NFE, issuerCnpj, recipientCnpj, issueDate,
                mapIdentificacao(ide), mapEmitente(emit), mapDestinatario(dest), mapTotais(icmsTot, ibsCbsTot), mapProdutos(infNFe));
    }

    private FiscalXmlIdentificacaoDTO mapIdentificacao(Element ide) {
        if (ide == null) {
            return null;
        }
        return new FiscalXmlIdentificacaoDTO(
                text(ide, "cUF"),
                text(ide, "natOp"),
                text(ide, "mod"),
                text(ide, "serie"),
                text(ide, "nNF"),
                parseDateTime(text(ide, "dhEmi")),
                parseDateTime(text(ide, "dhSaiEnt")),
                text(ide, "tpNF"),
                text(ide, "idDest"),
                text(ide, "cMunFG"),
                text(ide, "tpImp"),
                text(ide, "tpEmis"),
                text(ide, "cDV"),
                text(ide, "tpAmb"),
                text(ide, "finNFe"),
                text(ide, "indFinal"),
                text(ide, "indPres"),
                text(ide, "procEmi"),
                text(ide, "verProcEmi")
        );
    }

    private FiscalXmlEmitenteDTO mapEmitente(Element emit) {
        if (emit == null) {
            return null;
        }
        return new FiscalXmlEmitenteDTO(
                text(emit, "CNPJ"),
                text(emit, "CPF"),
                text(emit, "xNome"),
                text(emit, "xFant"),
                text(emit, "IE"),
                text(emit, "IEST"),
                text(emit, "IM"),
                text(emit, "CNAE"),
                text(emit, "CRT"),
                mapEndereco(first(emit, "enderEmit"))
        );
    }

    private FiscalXmlDestinatarioDTO mapDestinatario(Element dest) {
        if (dest == null) {
            return null;
        }
        return new FiscalXmlDestinatarioDTO(
                text(dest, "CNPJ"),
                text(dest, "CPF"),
                text(dest, "idEstrangeiro"),
                text(dest, "xNome"),
                text(dest, "IE"),
                text(dest, "indIEDest"),
                text(dest, "email"),
                mapEndereco(first(dest, "enderDest"))
        );
    }

    private FiscalXmlEnderecoDTO mapEndereco(Element ender) {
        if (ender == null) {
            return null;
        }
        return new FiscalXmlEnderecoDTO(
                text(ender, "xLgr"),
                text(ender, "nro"),
                text(ender, "xCpl"),
                text(ender, "xBairro"),
                text(ender, "cMun"),
                text(ender, "xMun"),
                text(ender, "UF"),
                text(ender, "CEP"),
                text(ender, "cPais"),
                text(ender, "xPais"),
                text(ender, "fone")
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
    private FiscalIbsCbsTotalsDTO mapIbsCbsTotais(Element ibsCbsTot) {
        if (ibsCbsTot == null) {
            return null;
        }
        Element gIbs = first(ibsCbsTot, "gIBS");
        Element gCbs = first(ibsCbsTot, "gCBS");
        return new FiscalIbsCbsTotalsDTO(
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
            Element prod = first(det, "prod");
            if (prod == null) {
                continue;
            }
            produtos.add(new FiscalXmlProdutoDTO(
                    intOf(det.getAttribute("nItem")),
                    text(prod, "cProd"),
                    text(prod, "cEAN"),
                    text(prod, "xProd"),
                    text(prod, "NCM"),
                    text(prod, "CFOP"),
                    text(prod, "uCom"),
                    decimalOf(prod, "qCom"),
                    decimalOf(prod, "vUnCom"),
                    decimalOf(prod, "vProd"),
                    text(prod, "uTrib"),
                    decimalOf(prod, "qTrib"),
                    decimalOf(prod, "vUnTrib"),
                    mapImpostoItem(prod, first(det, "imposto"))
            ));
        }
        return produtos;
    }

    private FiscalXmlImpostoItemDTO mapImpostoItem(Element prod, Element imposto) {
        if (imposto == null) {
            return null;
        }
        Element icms = firstRegimeGroup(first(imposto, "ICMS"));
        Element ipi = firstRegimeGroup(first(imposto, "IPI"));
        Element pis = firstRegimeGroup(first(imposto, "PIS"));
        Element cofins = firstRegimeGroup(first(imposto, "COFINS"));

        return new FiscalXmlImpostoItemDTO(
                icmsSituacao(icms),
                decimalOf(icms, "vBC"),
                decimalOf(icms, "pICMS"),
                decimalOf(icms, "vICMS"),
                text(ipi, "CST"),
                decimalOf(ipi, "vIPI"),
                text(pis, "CST"),
                decimalOf(pis, "vPIS"),
                text(cofins, "CST"),
                decimalOf(cofins, "vCOFINS"),
                mapTaxDetail(prod, icms, ipi, pis, cofins),
                mapIbsCbs(first(imposto, "IBSCBS"))
        );
    }

    private FiscalItemTaxDetailDTO mapTaxDetail(Element prod, Element icms, Element ipi, Element pis, Element cofins) {
        return new FiscalItemTaxDetailDTO(
                text(prod, "CEST"),
                text(prod, "cBenef"),
                text(icms, "orig"),
                text(icms, "CSOSN"),
                text(icms, "modBC"),
                decimalOf(icms, "pRedBC"),
                decimalOf(icms, "vICMSDeson"),
                text(icms, "modBCST"),
                decimalOf(icms, "pMVAST"),
                decimalOf(icms, "vBCST"),
                decimalOf(icms, "pICMSST"),
                decimalOf(icms, "vICMSST"),
                decimalOf(ipi, "vBC"),
                decimalOf(ipi, "pIPI"),
                decimalOf(pis, "vBC"),
                decimalOf(pis, "pPIS"),
                decimalOf(cofins, "vBC"),
                decimalOf(cofins, "pCOFINS"),
                decimalOf(icms, "pCredSN"),
                decimalOf(icms, "vCredICMSSN")
        );
    }

    /**
     * det/imposto/IBSCBS: CST and cClassTrib at the top, the values in gIBSCBS - one
     * subgroup per tax (gIBSUF, gIBSMun, gCBS), each with its rate, an optional reduction
     * (gRed/pRedAliq) and the value. A note with no IBSCBS group gets null.
     */
    private FiscalItemIbsCbsDTO mapIbsCbs(Element ibsCbs) {
        if (ibsCbs == null) {
            return null;
        }
        Element values = first(ibsCbs, "gIBSCBS");
        Element ibsUf = first(values, "gIBSUF");
        Element ibsMun = first(values, "gIBSMun");
        Element cbs = first(values, "gCBS");
        return new FiscalItemIbsCbsDTO(
                text(ibsCbs, "CST"),
                text(ibsCbs, "cClassTrib"),
                decimalOf(values, "vBC"),
                decimalOf(ibsUf, "pIBSUF"),
                decimalOf(first(ibsUf, "gRed"), "pRedAliq"),
                decimalOf(ibsUf, "vIBSUF"),
                decimalOf(ibsMun, "pIBSMun"),
                decimalOf(first(ibsMun, "gRed"), "pRedAliq"),
                decimalOf(ibsMun, "vIBSMun"),
                decimalOf(values, "vIBS"),
                decimalOf(cbs, "pCBS"),
                decimalOf(first(cbs, "gRed"), "pRedAliq"),
                decimalOf(cbs, "vCBS"),
                first(values, "gDif") != null
                        || first(values, "gDevTrib") != null
        );
    }

    private String icmsSituacao(Element icms) {
        String cst = text(icms, "CST");
        return cst != null ? cst : text(icms, "CSOSN");
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
        String value = text(parent, childLocalName);
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
        return new FiscalXmlMetadata(null, FiscalXmlMetadata.UNKNOWN, null, null, null, null, null, null, null, List.of());
    }

    private String normalizeAccessKey(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String digitsOnly = rawId.replaceAll("\\D", "");
        return digitsOnly.length() == 44 ? digitsOnly : null;
    }

    private LocalDateTime extractIssueDate(Element infoElement) {
        Element ide = first(infoElement, "ide");
        if (ide == null) {
            return null;
        }
        LocalDateTime dhEmi = parseDateTime(text(ide, "dhEmi"));
        if (dhEmi != null) {
            return dhEmi;
        }
        return parseDateTime(text(ide, "dEmi"));
    }
}
