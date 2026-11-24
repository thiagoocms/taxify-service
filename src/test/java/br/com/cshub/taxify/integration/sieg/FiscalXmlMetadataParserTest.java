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
    void parsesItemTaxDetailAndIbsCbs() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe>
                    <infNFe Id="NFe35260112345678000199550010000000011000000010" versao="4.00">
                      <ide><dhEmi>2026-09-01T10:15:00-03:00</dhEmi></ide>
                      <emit><CNPJ>12345678000199</CNPJ><CRT>3</CRT></emit>
                      <det nItem="1">
                        <prod>
                          <cProd>P001</cProd>
                          <NCM>22021000</NCM>
                          <CEST>0300700</CEST>
                          <cBenef>SP000001</cBenef>
                          <CFOP>5405</CFOP>
                          <vProd>100.00</vProd>
                        </prod>
                        <imposto>
                          <ICMS>
                            <ICMS10>
                              <orig>0</orig>
                              <CST>10</CST>
                              <modBC>3</modBC>
                              <vBC>100.00</vBC>
                              <pICMS>18.00</pICMS>
                              <vICMS>18.00</vICMS>
                              <modBCST>4</modBCST>
                              <pMVAST>40.00</pMVAST>
                              <vBCST>140.00</vBCST>
                              <pICMSST>18.00</pICMSST>
                              <vICMSST>7.20</vICMSST>
                            </ICMS10>
                          </ICMS>
                          <IPI><cEnq>999</cEnq><IPITrib><CST>50</CST><vBC>100.00</vBC><pIPI>5.00</pIPI><vIPI>5.00</vIPI></IPITrib></IPI>
                          <PIS><PISAliq><CST>01</CST><vBC>100.00</vBC><pPIS>1.65</pPIS><vPIS>1.65</vPIS></PISAliq></PIS>
                          <COFINS><COFINSAliq><CST>01</CST><vBC>100.00</vBC><pCOFINS>7.60</pCOFINS><vCOFINS>7.60</vCOFINS></COFINSAliq></COFINS>
                          <IBSCBS>
                            <CST>000</CST>
                            <cClassTrib>000001</cClassTrib>
                            <gIBSCBS>
                              <vBC>100.00</vBC>
                              <gIBSUF><pIBSUF>0.1000</pIBSUF><vIBSUF>0.10</vIBSUF></gIBSUF>
                              <gIBSMun><pIBSMun>0.0000</pIBSMun><vIBSMun>0.00</vIBSMun></gIBSMun>
                              <vIBS>0.10</vIBS>
                              <gCBS><pCBS>0.9000</pCBS><gRed><pRedAliq>60.0000</pRedAliq><pAliqEfet>0.3600</pAliqEfet></gRed><vCBS>0.36</vCBS></gCBS>
                            </gIBSCBS>
                          </IBSCBS>
                        </imposto>
                      </det>
                      <det nItem="2">
                        <prod><cProd>P002</cProd><CFOP>5102</CFOP><vProd>10.00</vProd></prod>
                        <imposto>
                          <ICMS><ICMSSN102><orig>2</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS>
                        </imposto>
                      </det>
                      <total>
                        <ICMSTot><vNF>110.00</vNF></ICMSTot>
                        <IBSCBSTot>
                          <vBCIBSCBS>100.00</vBCIBSCBS>
                          <gIBS>
                            <gIBSUF><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vIBSUF>0.10</vIBSUF></gIBSUF>
                            <gIBSMun><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vIBSMun>0.00</vIBSMun></gIBSMun>
                            <vIBS>0.10</vIBS>
                          </gIBS>
                          <gCBS><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vCBS>0.36</vCBS></gCBS>
                        </IBSCBSTot>
                      </total>
                    </infNFe>
                  </NFe>
                </nfeProc>
                """;

        FiscalXmlMetadata metadata = parser.parse(xml.getBytes(StandardCharsets.UTF_8));

        var impostos = metadata.produtos().get(0).impostos();
        var detalhe = impostos.detalhe();
        assertThat(detalhe.cest()).isEqualTo("0300700");
        assertThat(detalhe.codigoBeneficio()).isEqualTo("SP000001");
        assertThat(detalhe.origemMercadoria()).isEqualTo("0");
        assertThat(detalhe.csosn()).isNull();
        assertThat(detalhe.modalidadeBcIcms()).isEqualTo("3");
        assertThat(detalhe.mvaIcmsSt()).isEqualByComparingTo("40");
        assertThat(detalhe.baseCalculoIcmsSt()).isEqualByComparingTo("140");
        assertThat(detalhe.aliquotaIcmsSt()).isEqualByComparingTo("18");
        assertThat(detalhe.valorIcmsSt()).isEqualByComparingTo("7.20");
        assertThat(detalhe.baseCalculoIpi()).isEqualByComparingTo("100");
        assertThat(detalhe.aliquotaIpi()).isEqualByComparingTo("5");
        assertThat(detalhe.aliquotaPis()).isEqualByComparingTo("1.65");
        assertThat(detalhe.baseCalculoCofins()).isEqualByComparingTo("100");
        assertThat(detalhe.aliquotaCofins()).isEqualByComparingTo("7.60");

        var ibsCbs = impostos.ibsCbs();
        assertThat(ibsCbs.cst()).isEqualTo("000");
        assertThat(ibsCbs.classificacaoTributaria()).isEqualTo("000001");
        assertThat(ibsCbs.baseCalculo()).isEqualByComparingTo("100");
        assertThat(ibsCbs.aliquotaIbsUf()).isEqualByComparingTo("0.1");
        assertThat(ibsCbs.reducaoAliquotaIbsUf()).isNull();
        assertThat(ibsCbs.valorIbsUf()).isEqualByComparingTo("0.10");
        assertThat(ibsCbs.aliquotaIbsMun()).isEqualByComparingTo("0");
        assertThat(ibsCbs.valorIbs()).isEqualByComparingTo("0.10");
        assertThat(ibsCbs.aliquotaCbs()).isEqualByComparingTo("0.9");
        assertThat(ibsCbs.reducaoAliquotaCbs()).isEqualByComparingTo("60");
        assertThat(ibsCbs.valorCbs()).isEqualByComparingTo("0.36");

        var simples = metadata.produtos().get(1).impostos();
        assertThat(simples.situacaoTributariaIcms()).isEqualTo("102");
        assertThat(simples.detalhe().csosn()).isEqualTo("102");
        assertThat(simples.detalhe().origemMercadoria()).isEqualTo("2");
        assertThat(simples.ibsCbs()).isNull();

        var totais = metadata.totais().ibsCbs();
        assertThat(totais.baseCalculo()).isEqualByComparingTo("100");
        assertThat(totais.valorIbsUf()).isEqualByComparingTo("0.10");
        assertThat(totais.valorIbsMun()).isEqualByComparingTo("0");
        assertThat(totais.valorIbs()).isEqualByComparingTo("0.10");
        assertThat(totais.valorCbs()).isEqualByComparingTo("0.36");
    }

    /** CSOSN 101: the credit the Simples supplier passes on (LC 123/2006, art. 23, § 1º) - the Fronteira PE uses it. */
    @Test
    void simplesSupplierCredit() {
        String xml = """
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe>
                  <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                    <det nItem="1"><prod><cProd>P1</cProd><vProd>1000.00</vProd></prod>
                      <imposto><ICMS><ICMSSN101><orig>0</orig><CSOSN>101</CSOSN><pCredSN>2.5600</pCredSN>
                        <vCredICMSSN>25.60</vCredICMSSN></ICMSSN101></ICMS></imposto></det>
                    <total><ICMSTot><vNF>1000.00</vNF></ICMSTot></total>
                  </infNFe></NFe></nfeProc>
                """;

        var detalhe = parser.parse(xml.getBytes(StandardCharsets.UTF_8)).produtos().get(0).impostos().detalhe();

        assertThat(detalhe.csosn()).isEqualTo("101");
        assertThat(detalhe.aliquotaCreditoSn()).isEqualByComparingTo("2.56");
        assertThat(detalhe.valorCreditoSn()).isEqualByComparingTo("25.60");
    }

    @Test
    void noteWithoutIbsCbsHasNoIbsCbsGroups() {
        String xml = """
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe>
                  <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                    <det nItem="1"><prod><cProd>P1</cProd></prod>
                      <imposto><ICMS><ICMS00><orig>0</orig><CST>00</CST></ICMS00></ICMS></imposto></det>
                    <total><ICMSTot><vNF>1.00</vNF></ICMSTot></total>
                  </infNFe></NFe></nfeProc>
                """;

        FiscalXmlMetadata metadata = parser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(metadata.produtos().get(0).impostos().ibsCbs()).isNull();
        assertThat(metadata.totais().ibsCbs()).isNull();
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
