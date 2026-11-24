package br.com.cshub.taxify.integration.efisco;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** e-Fisco's own memória of NF 473 (registro 012.6.585664902-6) - carga líquida of the credenciamento 26. */
class EfiscoMemoryParserTest {

    private static final String NF_473 = """
            Cálculo de Nota Fiscal - 012.6.585664902-6
            Chave de acesso da NFe
            24261156158753000184550010000004731958741256
            (Cálculo baseado nas regras de Emitente com Regime Tributário Simples Nacional)
            Tipos de Cálculo
            Tipo Prioridade Prior. Padrão Interrompe Tipo Calc. Calcula Automático Desc. Red. TC Aparece Consist.
            CNAE (051412601 - 051412603) 0 0 + 20 = 20 N 7 S INDÚSTRIA ANTECIPADA S
            CREDENCIAMENTO (26) 2 2 + 25 = 27 S 26 S IND TEC CREDENCIADA S
            Alíquota InterEstadual
            AliquotaInterEstadual[0.1200000000] = AliquotaEmitente[12.0000000000] / 100
            Cálculo Mercadoria: 258 - ICMS-OP. INTERESTADU
            Valor Item Antes do Desconto
            ValorItemAntesDoDesconto[2400.0000000000] = QuantidadeMercadoria[600.0000000000] * ValorUnitarioMerc[4.0000000000]
            Valor de Partida Antecipado
            ValorPartidaAntecipado[2400.0000000000] = ValorItemLiquido[2400.0000000000] + (IPIItem[0.0000000000] * IPIIntegraBCAntecipado[1.0000000000])
            Diferencial de Alíquota
            DiferencialAliquotas[0.0850000000] = (AliquotaInterna[0.2050000000] - AliquotaInterEstadual[0.1200000000])
            Crédito Utilizado (Sem Crédito Especial)
            CreditoUtilizado[0.0000000000] = 0
            CreditoUtilizado[0.0000000000] = CreditoUtilizado[0.0000000000] + ICMSFreteProporcionalItem[0.0000000000]
            Base de Cálculo Item Antecipado
            BaseCalculoItemAntecipado[2400.0000000000] = ValorPartidaAntecipado[2400.0000000000]
            BaseCalculoItemAntecipado[2400.0000000000] = BaseCalculoItemAntecipado[2400.0000000000] * CoefAgregacaoNorteNE da Mercadoria[1.0000000000]
            ICMS Antecipado do Item (Mercadoria com Carga Liquida e sem Direito a Credito Presumido)
            ICMSAntecipadoItemCL[132.0000000000] = BaseCalculoItemAntecipado[2400.0000000000] * (PercentualCargaLiquida do TipoCalculo[5.5000000000] / 100)
            ICMSAntecipadoItem[132.0000000000] = ICMSAntecipadoItemCL[132.0000000000]
            """;

    @Test
    void readsTheNoteTheTypesAndTheItem() {
        EfiscoMemoryParser.Memory memory = EfiscoMemoryParser.parse(NF_473);

        assertThat(memory.chaveAcesso()).isEqualTo("24261156158753000184550010000004731958741256");
        assertThat(memory.regras()).isEqualTo("Emitente com Regime Tributário Simples Nacional");
        assertThat(memory.tipos()).extracting(EfiscoMemoryParser.CalculationType::tipo).containsExactly("CNAE", "CREDENCIAMENTO");
        assertThat(memory.tipos().get(1)).satisfies(t -> {
            assertThat(t.codigo()).isEqualTo("26");
            assertThat(t.prioridade()).isEqualTo(27);
            assertThat(t.interrompe()).isTrue();
            assertThat(t.descricao()).isEqualTo("IND TEC CREDENCIADA");
        });

        assertThat(memory.itens()).hasSize(1);
        EfiscoMemoryParser.Item item = memory.itens().get(0);
        assertThat(item.mercadoria()).isEqualTo("258 - ICMS-OP. INTERESTADU");
        assertThat(item.calculo()).isEqualTo("Mercadoria com Carga Liquida e sem Direito a Credito Presumido");
        assertThat(item.valorItem()).isEqualByComparingTo("2400");
        assertThat(item.partida()).isEqualByComparingTo("2400");
        assertThat(item.coefAgregacao()).isEqualByComparingTo("1");
        assertThat(item.base()).isEqualByComparingTo("2400");
        assertThat(item.aliquotaInterna()).isEqualByComparingTo("0.205");
        assertThat(item.aliquotaInterestadual()).isEqualByComparingTo("0.12");
        assertThat(item.percentualCargaLiquida()).isEqualByComparingTo("5.5");
        assertThat(item.credito()).isEqualByComparingTo("0");
        assertThat(item.icms()).isEqualByComparingTo("132");
        assertThat(memory.icmsTotal()).isEqualByComparingTo("132");
    }

    @Test
    void theHtmlBecomesTheSameLines() {
        String html = "<html><body><script>var x=1;</script><div>Menu</div><table><tr><td>Cálculo de Nota Fiscal - 012</td></tr>"
                + "<tr><td>CREDENCIAMENTO (26)</td><td>2</td><td>2 + 25 = 27</td><td>S</td><td>26</td><td>S</td><td>IND TEC CREDENCIADA</td><td>S</td></tr>"
                + "</table><p>ICMSAntecipadoItem[132.0000000000] = X</p></body></html>";

        String text = EfiscoMemoryParser.text(html);

        assertThat(text).startsWith("Cálculo de Nota Fiscal - 012").contains("CREDENCIAMENTO (26) 2 2 + 25 = 27 S 26 S IND TEC CREDENCIADA S")
                .doesNotContain("var x");
    }
}
