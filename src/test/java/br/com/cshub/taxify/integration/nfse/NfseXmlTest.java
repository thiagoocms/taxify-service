package br.com.cshub.taxify.integration.nfse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

public class NfseXmlTest {

    static final String NATIONAL_KEY = "26116062112233300018100000000000012345678901234567";

    /** National layout (ADN): ISS retained by the taker, PIS/COFINS retained, IRRF/CSLL/INSS. */
    public static String national(String tpRetIss, String tpRetPisCofins) {
        return """
                <NFSe versao="1.00" xmlns="http://www.sped.fazenda.gov.br/nfse">
                <infNFSe Id="NFS%s">
                  <xLocEmi>Recife</xLocEmi><nNFSe>123</nNFSe><cLocIncid>2611606</cLocIncid><xLocIncid>Recife</xLocIncid>
                  <cStat>100</cStat><dhProc>2026-11-10T10:00:00-03:00</dhProc>
                  <emit><CNPJ>11222333000181</CNPJ><IM>123</IM><xNome>PRESTADORA LTDA</xNome></emit>
                  <valores><vBC>1000.00</vBC><pAliqAplic>5.00</pAliqAplic><vISSQN>50.00</vISSQN><vTotalRet>211.50</vTotalRet><vLiq>788.50</vLiq></valores>
                  <DPS versao="1.00"><infDPS Id="DPS1">
                    <tpAmb>1</tpAmb><dhEmi>2026-11-10T09:30:00-03:00</dhEmi><serie>1</serie><nDPS>99</nDPS>
                    <dCompet>2026-11-01</dCompet><tpEmit>1</tpEmit><cLocEmi>2611606</cLocEmi>
                    <prest><CNPJ>11222333000181</CNPJ></prest>
                    <toma><CNPJ>99888777000166</CNPJ><xNome>TOMADORA SA</xNome></toma>
                    <serv><locPrest><cLocPrestacao>2611606</cLocPrestacao></locPrest>
                      <cServ><cTribNac>010101</cTribNac><cTribMun>001</cTribMun><xDescServ>Desenvolvimento de sistemas</xDescServ></cServ></serv>
                    <valores>
                      <vServPrest><vServ>1000.00</vServ></vServPrest>
                      <trib>
                        <tribMun><tribISSQN>1</tribISSQN><tpRetISSQN>%s</tpRetISSQN><pAliq>5.00</pAliq></tribMun>
                        <tribFed>
                          <piscofins><CST>01</CST><vBCPisCofins>1000.00</vBCPisCofins><pAliqPis>0.65</pAliqPis><pAliqCofins>3.00</pAliqCofins>
                            <vPis>6.50</vPis><vCofins>30.00</vCofins><tpRetPisCofins>%s</tpRetPisCofins></piscofins>
                          <vRetCP>110.00</vRetCP><vRetIRRF>15.00</vRetIRRF><vRetCSLL>10.00</vRetCSLL>
                        </tribFed>
                      </trib>
                    </valores>
                  </infDPS></DPS>
                </infNFSe></NFSe>
                """.formatted(NATIONAL_KEY, tpRetIss, tpRetPisCofins);
    }

    /** ABRASF 2.x as many cities issue it. */
    public static final String ABRASF = """
            <CompNfse xmlns="http://www.abrasf.org.br/nfse.xsd"><Nfse versao="2.02"><InfNfse Id="n1">
              <Numero>4567</Numero><CodigoVerificacao>ABC-123</CodigoVerificacao><DataEmissao>2026-10-05T14:00:00</DataEmissao>
              <ValoresNfse><BaseCalculo>2000.00</BaseCalculo><Aliquota>2.00</Aliquota><ValorIss>40.00</ValorIss><ValorLiquidoNfse>1930.00</ValorLiquidoNfse></ValoresNfse>
              <PrestadorServico><IdentificacaoPrestador><CpfCnpj><Cnpj>55444333000122</Cnpj></CpfCnpj></IdentificacaoPrestador>
                <RazaoSocial>CONSULTORIA ME</RazaoSocial></PrestadorServico>
              <OrgaoGerador><CodigoMunicipio>2611606</CodigoMunicipio><Uf>PE</Uf></OrgaoGerador>
              <DeclaracaoPrestacaoServico><InfDeclaracaoPrestacaoServico>
                <Competencia>2026-10-01</Competencia>
                <Servico><Valores><ValorServicos>2000.00</ValorServicos><ValorIr>30.00</ValorIr><ValorIss>40.00</ValorIss><Aliquota>2.00</Aliquota></Valores>
                  <IssRetido>1</IssRetido><ItemListaServico>17.01</ItemListaServico><Discriminacao>Assessoria contábil</Discriminacao>
                  <CodigoMunicipio>2611606</CodigoMunicipio><MunicipioIncidencia>2611606</MunicipioIncidencia></Servico>
                <Prestador><CpfCnpj><Cnpj>55444333000122</Cnpj></CpfCnpj></Prestador>
                <TomadorServico><IdentificacaoTomador><CpfCnpj><Cnpj>11222333000181</Cnpj></CpfCnpj></IdentificacaoTomador>
                  <RazaoSocial>EMPRESA TOMADORA LTDA</RazaoSocial></TomadorServico>
              </InfDeclaracaoPrestacaoServico></DeclaracaoPrestacaoServico>
            </InfNfse></Nfse></CompNfse>
            """;

    private static NfseXml parse(String xml) {
        return NfseXml.parse(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();
    }

    @Test
    void readsTheNationalLayoutWithItsRetentions() {
        NfseXml nfse = parse(national("2", "1"));

        assertThat(nfse.layout()).isEqualTo("NACIONAL");
        assertThat(nfse.accessKey()).isEqualTo(NATIONAL_KEY);
        assertThat(nfse.numero()).isEqualTo("123");
        assertThat(nfse.competence()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(nfse.prestadorDocumento()).isEqualTo("11222333000181");
        assertThat(nfse.prestadorNome()).isEqualTo("PRESTADORA LTDA");
        assertThat(nfse.tomadorDocumento()).isEqualTo("99888777000166");
        assertThat(nfse.municipioIncidencia()).isEqualTo("2611606");
        assertThat(nfse.codigoServico()).isEqualTo("010101");
        assertThat(nfse.descricaoServico()).isEqualTo("Desenvolvimento de sistemas");
        assertThat(nfse.valorServico()).isEqualByComparingTo("1000.00");
        assertThat(nfse.valorIss()).isEqualByComparingTo("50.00"); // the NFS-e's own valores, not the DPS's
        assertThat(nfse.aliquotaIss()).isEqualByComparingTo("5.00");
        assertThat(nfse.issRetido()).isTrue();
        assertThat(nfse.valorIssRetido()).isEqualByComparingTo("50.00");
        assertThat(nfse.pisCofinsRetidos()).isTrue();
        assertThat(nfse.valorPis()).isEqualByComparingTo("6.50");
        assertThat(nfse.valorInss()).isEqualByComparingTo("110.00");
        assertThat(nfse.valorIrrf()).isEqualByComparingTo("15.00");
        assertThat(nfse.valorCsll()).isEqualByComparingTo("10.00");
        assertThat(nfse.valorLiquido()).isEqualByComparingTo("788.50");
    }

    @Test
    void nationalIssNotRetainedAndPisCofinsOnlyInformed() {
        NfseXml nfse = parse(national("1", "2"));

        assertThat(nfse.issRetido()).isFalse();
        assertThat(nfse.valorIssRetido()).isNull();
        assertThat(nfse.pisCofinsRetidos()).isFalse();
    }

    @Test
    void readsTheAbrasfLayout() {
        NfseXml nfse = parse(ABRASF);

        assertThat(nfse.layout()).isEqualTo("ABRASF");
        assertThat(nfse.accessKey()).isEqualTo("ABRASF-2611606-55444333000122-4567");
        assertThat(nfse.codigoVerificacao()).isEqualTo("ABC-123");
        assertThat(nfse.competence()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(nfse.prestadorNome()).isEqualTo("CONSULTORIA ME");
        assertThat(nfse.tomadorDocumento()).isEqualTo("11222333000181");
        assertThat(nfse.tomadorNome()).isEqualTo("EMPRESA TOMADORA LTDA");
        assertThat(nfse.codigoServico()).isEqualTo("17.01");
        assertThat(nfse.issRetido()).isTrue();
        assertThat(nfse.valorIssRetido()).isEqualByComparingTo("40.00");
        assertThat(nfse.valorIrrf()).isEqualByComparingTo("30.00");
        assertThat(nfse.pisCofinsRetidos()).isFalse();
        assertThat(nfse.valorLiquido()).isEqualByComparingTo("1930.00");
        assertThat(nfse.canceled()).isFalse();
    }

    @Test
    void anAbrasfCancellationIsRead() {
        String canceled = ABRASF.replace("</Nfse>", "</Nfse><NfseCancelamento><Confirmacao/></NfseCancelamento>");

        assertThat(parse(canceled).canceled()).isTrue();
    }

    @Test
    void anNfeIsNotAnNfse() {
        byte[] nfe = "<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\"><NFe><infNFe Id=\"NFe1\"/></NFe></nfeProc>"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(NfseXml.looksLikeNfse(nfe)).isFalse();
        assertThat(NfseXml.parse(nfe)).isEmpty();
        assertThat(NfseXml.looksLikeNfse(ABRASF.getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(NfseXml.looksLikeNfse(national("1", "2").getBytes(StandardCharsets.UTF_8))).isTrue();
    }
}
