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

        assertThat(memory.accessKey()).isEqualTo("24261156158753000184550010000004731958741256");
        assertThat(memory.rules()).isEqualTo("Emitente com Regime Tributário Simples Nacional");
        assertThat(memory.types()).extracting(EfiscoMemoryParser.CalculationType::tipo).containsExactly("CNAE", "CREDENCIAMENTO");
        assertThat(memory.types().get(1)).satisfies(t -> {
            assertThat(t.codigo()).isEqualTo("26");
            assertThat(t.prioridade()).isEqualTo(27);
            assertThat(t.interrompe()).isTrue();
            assertThat(t.descricao()).isEqualTo("IND TEC CREDENCIADA");
        });

        assertThat(memory.items()).hasSize(1);
        EfiscoMemoryParser.Item item = memory.items().get(0);
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

    /**
     * The detail page as e-Fisco serves it (NF 752080): values with spaces inside the brackets, and one "Cálculo
     * Mercadoria" for the whole note - e-Fisco groups the items by its own mercadoria code, not by the note's items.
     */
    @Test
    void readsTheDetailPageWithSpacedValuesAndTheMercadoriaGroup() {
        String detail = """
                Cálculo de Nota Fiscal - 012.6.585967968-6
                CNAE (051412601 - 051412603) 0 0 + 20 = 20 N 7 S INDÚSTRIA ANTECIPADA S
                CREDENCIAMENTO (26) 2 2 + 25 = 27 S 26 S IND TEC CREDENCIADA S
                ICMSCalculado[ 1502.4100000000 ] = (ValorTotalProdutos[ 21463.0000000000 ] + ValorFrete[ 0.0000000000 ]) * ValorAliquotaEmitente[ 7.0000000000 ] / 100
                Cálculo Mercadoria: 258 - ICMS-OP. INTERESTADU
                ValorItemAntesDoDesconto[ 21462.9999999900 ] = QuantidadeMercadoria[ 300.0000000000 ] * ValorUnitarioMerc[ 71.5433333333 ]
                ICMSNormalItem[ 1502.4099999993 ] = BaseCalculoItemNormal[ 21462.9999999900 ] * AliquotaInterEstadual[ 0.0700000000 ]
                CreditoUtilizado[ 1502.4099999993 ] = CFLAItem[ 1502.4099999993 ]
                BaseCalculoItemAntecipado[ 21462.9999999900 ] = ValorPartidaAntecipado[ 21462.9999999900 ]
                BaseCalculoItemAntecipado[ 21462.9999999900 ] = BaseCalculoItemAntecipado[ 21462.9999999900 ] * CoefAgregacaoSulSudeste da Mercadoria[ 1.0000000000 ]
                ICMS Antecipado do Item (Mercadoria com Carga Liquida e sem Direito a Credito Presumido)
                ICMSAntecipadoItemCL[ 1180.4649999995 ] = BaseCalculoItemAntecipado[ 21462.9999999900 ] * (PercentualCargaLiquida do TipoCalculo[ 5.5000000000 ] / 100)
                ICMSAntecipadoItem[ 1180.4649999995 ] = ICMSAntecipadoItemCL[ 1180.4649999995 ]
                """;

        EfiscoMemoryParser.Memory memory = EfiscoMemoryParser.parse(detail);

        assertThat(memory.items()).hasSize(1);
        EfiscoMemoryParser.Item group = memory.items().get(0);
        assertThat(group.mercadoria()).isEqualTo("258 - ICMS-OP. INTERESTADU");
        assertThat(group.valorItem()).isEqualByComparingTo("21462.99999999");
        assertThat(group.partida()).isEqualByComparingTo("21462.99999999");
        assertThat(group.coefAgregacao()).isEqualByComparingTo("1");
        assertThat(group.aliquotaInterestadual()).isEqualByComparingTo("0.07");
        assertThat(group.percentualCargaLiquida()).isEqualByComparingTo("5.5");
        assertThat(group.credito()).isEqualByComparingTo("1502.4099999993");
        assertThat(group.icms()).isEqualByComparingTo("1180.4649999995");
        assertThat(memory.icmsTotal()).isEqualByComparingTo("1180.4649999995");
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
