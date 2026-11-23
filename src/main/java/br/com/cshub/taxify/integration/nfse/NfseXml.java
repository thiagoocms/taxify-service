package br.com.cshub.taxify.integration.nfse;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * A service invoice (NFS-e) read from its XML, in either layout:
 * <ul>
 *   <li>NACIONAL - the national standard (Ambiente Nacional / ADN, root {@code NFSe}/{@code infNFSe},
 *       key = the 50-digit "chave de acesso");</li>
 *   <li>ABRASF - the municipal standard most cities used before (versions 1.0 and 2.x,
 *       {@code CompNfse}/{@code Nfse}/{@code InfNfse}); no national key, so it's built from city,
 *       provider and number.</li>
 * </ul>
 * Retentions follow the roadmap (M1): national ISS retained when tpRetISSQN is 2 or 3, PIS/COFINS
 * only when tpRetPisCofins = 1, and vRetCP / vRetIRRF / vRetCSLL; ABRASF IssRetido = 1 and the
 * federal values (ValorIr, ValorPis, ValorCofins, ValorCsll, ValorInss), which the layout only
 * fills when retained.
 */
public record NfseXml(
        String accessKey,
        String layout,
        String numero,
        String codigoVerificacao,
        LocalDateTime issueDate,
        LocalDate competence,
        String prestadorDocumento,
        String prestadorNome,
        String prestadorMunicipio,
        String tomadorDocumento,
        String tomadorNome,
        String municipioIncidencia,
        String codigoServico,
        String codigoTributacaoMunicipal,
        String descricaoServico,
        BigDecimal valorServico,
        BigDecimal valorDeducoes,
        BigDecimal baseCalculo,
        BigDecimal aliquotaIss,
        BigDecimal valorIss,
        boolean issRetido,
        BigDecimal valorIssRetido,
        BigDecimal valorIrrf,
        BigDecimal valorPis,
        BigDecimal valorCofins,
        boolean pisCofinsRetidos,
        BigDecimal valorCsll,
        BigDecimal valorInss,
        BigDecimal valorLiquido,
        boolean canceled
) {

    public static final String NACIONAL = "NACIONAL";
    public static final String ABRASF = "ABRASF";

    /** Whether these bytes are an NFS-e (either layout) - cheap enough to route uploads. */
    public static boolean looksLikeNfse(byte[] xml) {
        if (xml == null) {
            return false;
        }
        String head = new String(xml, 0, Math.min(xml.length, 4096), java.nio.charset.StandardCharsets.UTF_8);
        return head.contains("infNFSe") || head.contains("InfNfse") || head.contains("CompNfse")
                || head.contains("sped.fazenda.gov.br/nfse");
    }

    /** Empty when the XML isn't an NFS-e this reader understands (or has no number to identify it). */
    public static Optional<NfseXml> parse(byte[] xml) {
        Document doc;
        try {
            doc = FiscalXmlDomParser.parse(xml);
        } catch (Exception e) {
            return Optional.empty();
        }
        Element national = first(doc.getDocumentElement(), "infNFSe");
        if (national != null) {
            return national(national);
        }
        Element abrasf = first(doc.getDocumentElement(), "InfNfse");
        if (abrasf != null) {
            boolean canceled = first(doc.getDocumentElement(), "NfseCancelamento") != null
                    || first(doc.getDocumentElement(), "CancelamentoNfse") != null;
            return abrasf(abrasf, canceled);
        }
        return Optional.empty();
    }

    private static Optional<NfseXml> national(Element inf) {
        String id = inf.getAttribute("Id");
        String key = id == null ? "" : id.replaceAll("\\D", "");
        Element dps = first(inf, "infDPS");
        if (key.isEmpty() || dps == null) {
            return Optional.empty();
        }
        Element emit = child(inf, "emit");
        Element nfseValues = child(inf, "valores");
        Element prest = child(dps, "prest");
        Element toma = child(dps, "toma");
        Element serv = child(dps, "serv");
        Element dpsValues = child(dps, "valores");
        Element trib = first(dpsValues, "trib");
        Element tribMun = first(trib, "tribMun");
        Element tribFed = first(trib, "tribFed");
        Element pisCofins = first(tribFed, "piscofins");

        String tpRetIss = text(tribMun, "tpRetISSQN");
        boolean issRetido = "2".equals(tpRetIss) || "3".equals(tpRetIss);
        BigDecimal valorIss = decimal(text(nfseValues, "vISSQN"));
        boolean pisCofinsRetidos = "1".equals(text(pisCofins, "tpRetPisCofins"));
        String prestadorDoc = document(emit != null ? emit : prest);
        String prestadorNome = text(emit, "xNome");
        if (prestadorNome == null) {
            prestadorNome = text(prest, "xNome");
        }
        return Optional.of(new NfseXml(
                key,
                NACIONAL,
                text(inf, "nNFSe"),
                null,
                dateTime(text(dps, "dhEmi")),
                date(text(dps, "dCompet")),
                prestadorDoc,
                truncate(prestadorNome, 150),
                text(dps, "cLocEmi"),
                document(toma),
                truncate(text(toma, "xNome"), 150),
                text(inf, "cLocIncid"),
                text(first(serv, "cServ"), "cTribNac"),
                text(first(serv, "cServ"), "cTribMun"),
                truncate(text(first(serv, "cServ"), "xDescServ"), 1000),
                decimal(text(first(dpsValues, "vServPrest"), "vServ")),
                decimal(text(first(dpsValues, "vDedRed"), "vDR")),
                decimal(text(nfseValues, "vBC")),
                decimal(firstNonNull(text(nfseValues, "pAliqAplic"), text(tribMun, "pAliq"))),
                valorIss,
                issRetido,
                issRetido ? valorIss : null,
                decimal(text(tribFed, "vRetIRRF")),
                decimal(text(pisCofins, "vPis")),
                decimal(text(pisCofins, "vCofins")),
                pisCofinsRetidos,
                decimal(text(tribFed, "vRetCSLL")),
                decimal(text(tribFed, "vRetCP")),
                decimal(text(nfseValues, "vLiq")),
                false));
    }

    private static Optional<NfseXml> abrasf(Element inf, boolean canceled) {
        String numero = directText(inf, "Numero");
        if (numero == null) {
            numero = text(inf, "Numero");
        }
        if (numero == null) {
            return Optional.empty();
        }
        Element prestador = firstNonNull(first(inf, "PrestadorServico"), first(inf, "Prestador"));
        Element tomador = firstNonNull(first(inf, "TomadorServico"), first(inf, "Tomador"));
        Element servico = first(inf, "Servico");
        Element valores = first(servico, "Valores");
        Element valoresNfse = first(inf, "ValoresNfse");
        String municipioGerador = text(first(inf, "OrgaoGerador"), "CodigoMunicipio");
        String prestadorDoc = abrasfDocument(prestador);

        String issRetidoFlag = firstNonNull(text(servico, "IssRetido"), text(valores, "IssRetido"));
        boolean issRetido = "1".equals(issRetidoFlag);
        BigDecimal valorIss = decimal(firstNonNull(text(valoresNfse, "ValorIss"), text(valores, "ValorIss")));
        BigDecimal valorIssRetido = decimal(text(valores, "ValorIssRetido"));
        BigDecimal pis = decimal(text(valores, "ValorPis"));
        BigDecimal cofins = decimal(text(valores, "ValorCofins"));
        String key = "ABRASF-" + (municipioGerador == null ? "0" : municipioGerador) + "-"
                + (prestadorDoc == null ? "0" : prestadorDoc) + "-" + numero.replaceAll("\\s", "");
        return Optional.of(new NfseXml(
                truncate(key, 80),
                ABRASF,
                numero,
                text(inf, "CodigoVerificacao"),
                dateTime(text(inf, "DataEmissao")),
                date(text(inf, "Competencia")),
                prestadorDoc,
                truncate(firstNonNull(text(prestador, "RazaoSocial"), text(prestador, "NomeFantasia")), 150),
                firstNonNull(municipioGerador, text(first(prestador, "Endereco"), "CodigoMunicipio")),
                abrasfDocument(tomador),
                truncate(text(tomador, "RazaoSocial"), 150),
                firstNonNull(text(servico, "MunicipioIncidencia"), text(servico, "CodigoMunicipio")),
                text(servico, "ItemListaServico"),
                text(servico, "CodigoTributacaoMunicipio"),
                truncate(text(servico, "Discriminacao"), 1000),
                decimal(text(valores, "ValorServicos")),
                decimal(text(valores, "ValorDeducoes")),
                decimal(firstNonNull(text(valoresNfse, "BaseCalculo"), text(valores, "BaseCalculo"))),
                decimal(firstNonNull(text(valoresNfse, "Aliquota"), text(valores, "Aliquota"))),
                valorIss,
                issRetido,
                issRetido ? (valorIssRetido != null && valorIssRetido.signum() > 0 ? valorIssRetido : valorIss) : null,
                decimal(text(valores, "ValorIr")),
                pis,
                cofins,
                (pis != null && pis.signum() > 0) || (cofins != null && cofins.signum() > 0),
                decimal(text(valores, "ValorCsll")),
                decimal(text(valores, "ValorInss")),
                decimal(firstNonNull(text(valoresNfse, "ValorLiquidoNfse"), text(valores, "ValorLiquidoNfse"))),
                canceled));
    }

    /** CNPJ, CPF (or nothing) of a national party element. */
    private static String document(Element party) {
        return firstNonNull(text(party, "CNPJ"), text(party, "CPF"));
    }

    /** ABRASF: CpfCnpj/Cnpj, CpfCnpj/Cpf or a bare Cnpj, anywhere under the party. */
    private static String abrasfDocument(Element party) {
        String value = firstNonNull(text(party, "Cnpj"), text(party, "Cpf"));
        return value == null ? null : value.replaceAll("\\D", "");
    }

    private static Element first(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    /** A direct child only - "valores" exists both in infNFSe and inside the DPS. */
    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String directText(Element parent, String localName) {
        Element child = child(parent, localName);
        return child == null ? null : blankToNull(child.getTextContent());
    }

    private static String text(Element parent, String localName) {
        Element element = first(parent, localName);
        return element == null ? null : blankToNull(element.getTextContent());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static BigDecimal decimal(String value) {
        try {
            return value == null ? null : new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ignored) {
                LocalDate day = date(value);
                return day == null ? null : day.atStartOfDay();
            }
        }
    }
}
