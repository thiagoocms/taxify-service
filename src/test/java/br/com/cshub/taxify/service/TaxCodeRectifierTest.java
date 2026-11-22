package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaxCodeRectifierTest {

    private static final String DET = """
            <det xmlns="http://www.portalfiscal.inf.br/nfe" nItem="1">
              <prod><cProd>P1</cProd><NCM>22021000</NCM><CEST>0300799</CEST><CFOP>5102</CFOP></prod>
              <imposto>
                <ICMS><ICMS40><orig>1</orig><CST>41</CST></ICMS40></ICMS>
                <IPI><cEnq>999</cEnq><IPINT><CST>53</CST></IPINT></IPI>
                <PIS><PISAliq><CST>01</CST><vBC>10.00</vBC><pPIS>1.65</pPIS><vPIS>0.17</vPIS></PISAliq></PIS>
                <COFINS><COFINSNT><CST>06</CST></COFINSNT></COFINS>
                <IBSCBS><CST>000</CST><cClassTrib>000001</cClassTrib></IBSCBS>
              </imposto>
            </det>
            """;

    @Test
    void rewritesCodesThatStayInTheSameXmlGroup() throws Exception {
        Element det = parse(DET);
        List<String> corrections = new ArrayList<>();

        TaxCodeRectifier.apply(det, new ProductTaxRule("0", "40", null, null, "0300700", "52", null, "02", null, "04",
                null, "000", "000002"), "P1", corrections);

        assertThat(text(det, "orig")).isEqualTo("0");
        assertThat(text(det, "CEST")).isEqualTo("0300700");
        assertThat(firstIn(det, "ICMS40", "CST")).isEqualTo("40");
        assertThat(firstIn(det, "IPINT", "CST")).isEqualTo("52");
        assertThat(firstIn(det, "PISAliq", "CST")).isEqualTo("02");
        assertThat(firstIn(det, "COFINSNT", "CST")).isEqualTo("04");
        assertThat(text(det, "cClassTrib")).isEqualTo("000002");
        assertThat(corrections).hasSize(7)
                .contains("Item P1: CST do ICMS 41 -> 40", "Item P1: cClassTrib 000001 -> 000002");
    }

    @Test
    void leavesAloneCodesThatNeedAnotherGroupAndNeverTouchesValues() throws Exception {
        Element det = parse(DET);
        List<String> corrections = new ArrayList<>();

        // CST 00 needs ICMS00, IPI 50 needs IPITrib, PIS 06 needs PISNT, cClassTrib of another CST
        TaxCodeRectifier.apply(det, new ProductTaxRule(null, "00", null, null, null, "50", null, "06", null, null,
                null, null, "200001"), "P1", corrections);

        assertThat(corrections).isEmpty();
        assertThat(firstIn(det, "ICMS40", "CST")).isEqualTo("41");
        assertThat(firstIn(det, "IPINT", "CST")).isEqualTo("53");
        assertThat(firstIn(det, "PISAliq", "CST")).isEqualTo("01");
        assertThat(text(det, "vPIS")).isEqualTo("0.17");
        assertThat(text(det, "cClassTrib")).isEqualTo("000001");
    }

    private static Element parse(String xml) throws Exception {
        Document document = FiscalXmlDomParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        return document.getDocumentElement();
    }

    private static String text(Element parent, String name) {
        return parent.getElementsByTagNameNS("*", name).item(0).getTextContent();
    }

    private static String firstIn(Element parent, String group, String name) {
        return text((Element) parent.getElementsByTagNameNS("*", group).item(0), name);
    }
}
