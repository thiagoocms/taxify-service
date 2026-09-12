package br.com.cshub.taxify.integration.sieg;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalXmlMetadataParserTest {

    private final FiscalXmlMetadataParser parser = new FiscalXmlMetadataParser();

    @Test
    void parsesAccessKeyCnpjsAndIssueDateFromNfe() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe>
                    <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                      <ide>
                        <dhEmi>2026-09-01T10:15:00-03:00</dhEmi>
                      </ide>
                      <emit>
                        <CNPJ>12345678000199</CNPJ>
                      </emit>
                      <dest>
                        <CNPJ>98765432000188</CNPJ>
                      </dest>
                    </infNFe>
                  </NFe>
                </nfeProc>
                """;

        FiscalXmlMetadata metadata = parser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(metadata.documentType()).isEqualTo("NFE");
        assertThat(metadata.accessKey()).isEqualTo("35240112345678000199550010000000011000000010");
        assertThat(metadata.issuerCnpj()).isEqualTo("12345678000199");
        assertThat(metadata.recipientCnpj()).isEqualTo("98765432000188");
        assertThat(metadata.issueDate()).isNotNull();
    }

    @Test
    void parsesFullNfeHeaderAndLineItems() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe>
                    <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                      <ide>
                        <cUF>35</cUF>
                        <natOp>Venda</natOp>
                        <mod>55</mod>
                        <serie>1</serie>
                        <nNF>123</nNF>
                        <dhEmi>2026-09-01T10:15:00-03:00</dhEmi>
                        <tpNF>1</tpNF>
                        <idDest>1</idDest>
                        <cMunFG>3550308</cMunFG>
                        <tpImp>1</tpImp>
                        <tpEmis>1</tpEmis>
                        <cDV>0</cDV>
                        <tpAmb>1</tpAmb>
                        <finNFe>1</finNFe>
                        <indFinal>1</indFinal>
                        <indPres>1</indPres>
                        <procEmi>0</procEmi>
                        <verProcEmi>1.0</verProcEmi>
                      </ide>
                      <emit>
                        <CNPJ>12345678000199</CNPJ>
                        <xNome>Emitente Teste</xNome>
                        <xFant>Emitente Fantasia</xFant>
                        <IE>123456789</IE>
                        <CRT>3</CRT>
                        <enderEmit>
                          <xLgr>Rua Um</xLgr>
                          <nro>100</nro>
                          <xBairro>Centro</xBairro>
                          <cMun>3550308</cMun>
                          <xMun>Sao Paulo</xMun>
                          <UF>SP</UF>
                          <CEP>01000000</CEP>
                        </enderEmit>
                      </emit>
                      <dest>
                        <CNPJ>98765432000188</CNPJ>
                        <xNome>Destinatario Teste</xNome>
                        <indIEDest>1</indIEDest>
                        <IE>987654321</IE>
                        <enderDest>
                          <xLgr>Rua Dois</xLgr>
                          <nro>200</nro>
                          <xBairro>Bairro</xBairro>
                          <cMun>3550308</cMun>
                          <xMun>Sao Paulo</xMun>
                          <UF>SP</UF>
                          <CEP>02000000</CEP>
                        </enderDest>
                      </dest>
                      <det nItem="1">
                        <prod>
                          <cProd>P001</cProd>
                          <xProd>Produto Um</xProd>
                          <NCM>12345678</NCM>
                          <CFOP>5102</CFOP>
                          <uCom>UN</uCom>
                          <qCom>2.0000</qCom>
                          <vUnCom>10.00</vUnCom>
                          <vProd>20.00</vProd>
                        </prod>
                        <imposto>
                          <ICMS>
                            <ICMS00>
                              <CST>00</CST>
                              <vBC>20.00</vBC>
                              <pICMS>18.00</pICMS>
                              <vICMS>3.60</vICMS>
                            </ICMS00>
                          </ICMS>
                          <IPI>
                            <IPITrib>
                              <CST>50</CST>
                              <vIPI>1.00</vIPI>
                            </IPITrib>
                          </IPI>
                          <PIS>
                            <PISAliq>
                              <CST>01</CST>
                              <vPIS>0.33</vPIS>
                            </PISAliq>
                          </PIS>
                          <COFINS>
                            <COFINSAliq>
                              <CST>01</CST>
                              <vCOFINS>1.52</vCOFINS>
                            </COFINSAliq>
                          </COFINS>
                        </imposto>
                      </det>
                      <det nItem="2">
                        <prod>
                          <cProd>P002</cProd>
                          <xProd>Produto Dois</xProd>
                          <NCM>87654321</NCM>
                          <CFOP>5102</CFOP>
                          <uCom>UN</uCom>
                          <qCom>1.0000</qCom>
                          <vUnCom>5.00</vUnCom>
                          <vProd>5.00</vProd>
                        </prod>
                      </det>
                      <total>
                        <ICMSTot>
                          <vBC>20.00</vBC>
                          <vICMS>3.60</vICMS>
                          <vProd>25.00</vProd>
                          <vFrete>0.00</vFrete>
                          <vDesc>0.00</vDesc>
                          <vIPI>1.00</vIPI>
                          <vPIS>0.33</vPIS>
                          <vCOFINS>1.52</vCOFINS>
                          <vNF>26.00</vNF>
                        </ICMSTot>
                      </total>
                    </infNFe>
                  </NFe>
                </nfeProc>
                """;

        FiscalXmlMetadata metadata = parser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(metadata.identificacao()).isNotNull();
        assertThat(metadata.identificacao().naturezaOperacao()).isEqualTo("Venda");
        assertThat(metadata.identificacao().numero()).isEqualTo("123");
        assertThat(metadata.identificacao().serie()).isEqualTo("1");

        assertThat(metadata.emitente()).isNotNull();
        assertThat(metadata.emitente().nome()).isEqualTo("Emitente Teste");
        assertThat(metadata.emitente().endereco().municipio()).isEqualTo("Sao Paulo");
        assertThat(metadata.emitente().endereco().uf()).isEqualTo("SP");

        assertThat(metadata.destinatario()).isNotNull();
        assertThat(metadata.destinatario().nome()).isEqualTo("Destinatario Teste");
        assertThat(metadata.destinatario().endereco().cep()).isEqualTo("02000000");

        assertThat(metadata.totais()).isNotNull();
        assertThat(metadata.totais().valorTotalNota()).isEqualByComparingTo("26.00");
        assertThat(metadata.totais().valorProdutos()).isEqualByComparingTo("25.00");

        assertThat(metadata.produtos()).hasSize(2);
        var primeiroItem = metadata.produtos().get(0);
        assertThat(primeiroItem.numeroItem()).isEqualTo(1);
        assertThat(primeiroItem.descricao()).isEqualTo("Produto Um");
        assertThat(primeiroItem.valorTotal()).isEqualByComparingTo("20.00");
        assertThat(primeiroItem.impostos()).isNotNull();
        assertThat(primeiroItem.impostos().situacaoTributariaIcms()).isEqualTo("00");
        assertThat(primeiroItem.impostos().valorIcms()).isEqualByComparingTo("3.60");
        assertThat(primeiroItem.impostos().valorIpi()).isEqualByComparingTo("1.00");
        assertThat(primeiroItem.impostos().valorPis()).isEqualByComparingTo("0.33");
        assertThat(primeiroItem.impostos().valorCofins()).isEqualByComparingTo("1.52");

        var segundoItem = metadata.produtos().get(1);
        assertThat(segundoItem.numeroItem()).isEqualTo(2);
        assertThat(segundoItem.descricao()).isEqualTo("Produto Dois");
    }

    @Test
    void parsesCteAsCteDocumentType() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <cteProc xmlns="http://www.portalfiscal.inf.br/cte">
                  <CTe>
                    <infCte Id="CTe35240112345678000199570010000000011000000010" versao="4.00">
                      <ide>
                        <dhEmi>2026-09-01T10:15:00-03:00</dhEmi>
                      </ide>
                      <emit>
                        <CNPJ>12345678000199</CNPJ>
                      </emit>
                    </infCte>
                  </CTe>
                </cteProc>
                """;

        FiscalXmlMetadata metadata = parser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(metadata.documentType()).isEqualTo("CTE");
        assertThat(metadata.accessKey()).isEqualTo("35240112345678000199570010000000011000000010");
    }

    @Test
    void returnsUnknownForUnrecognizedXml() {
        String xml = "<somethingElse></somethingElse>";

        FiscalXmlMetadata metadata = parser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(metadata.documentType()).isEqualTo("UNKNOWN");
        assertThat(metadata.accessKey()).isNull();
    }

    @Test
    void returnsUnknownForGarbageBytesInsteadOfThrowing() {
        FiscalXmlMetadata metadata = parser.parse("not xml at all".getBytes(StandardCharsets.UTF_8));

        assertThat(metadata.documentType()).isEqualTo("UNKNOWN");
    }
}
