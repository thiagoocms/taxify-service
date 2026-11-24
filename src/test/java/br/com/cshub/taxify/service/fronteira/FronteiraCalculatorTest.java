package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import br.com.cshub.taxify.service.fronteira.FronteiraCalculator.Context;
import br.com.cshub.taxify.service.fronteira.FronteiraCalculator.ItemResult;
import br.com.cshub.taxify.service.fronteira.FronteiraCalculator.Treatment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Fronteira PE calculation against cases checked by hand (identification fictitious). A case only
 * passes if the value charged by SEFAZ-PE falls in the range of the rounding methods.
 */
class FronteiraCalculatorTest {

    private static FronteiraRule rule(FronteiraRuleKind kind) {
        FronteiraRule rule = new FronteiraRule();
        rule.setId(UUID.randomUUID());
        rule.setKind(kind);
        rule.setEnabled(true);
        rule.setReviewedAt(LocalDateTime.now());
        return rule;
    }

    private static FronteiraRule interna(String ncm, String rate) {
        FronteiraRule rule = rule(FronteiraRuleKind.ALIQUOTA_INTERNA);
        rule.setNcm(ncm);
        rule.setAliquota(new BigDecimal(rate));
        return rule;
    }

    private static FiscalXmlDocument note(String uf, String valorProdutos) {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        document.setIssuerCnpj("11111111000111");
        document.setEmitenteUf(uf);
        document.setDestinatarioUf("PE");
        document.setIssueDate(LocalDateTime.of(2026, 10, 10, 10, 0));
        document.setValorProdutos(new BigDecimal(valorProdutos));
        return document;
    }

    private static FiscalXmlDocumentItem item(FiscalXmlDocument document, String codigo, String ncm, String cfop, String valor,
                                              String aliquota, String icms, String origem, String stRetido) {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setDocument(document);
        item.setNumeroItem(1);
        item.setCodigo(codigo);
        item.setNcm(ncm);
        item.setCfop(cfop);
        item.setValorTotal(new BigDecimal(valor));
        item.setAliquotaIcms(aliquota == null ? null : new BigDecimal(aliquota));
        item.setValorIcms(icms == null ? null : new BigDecimal(icms));
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, origem, null, null, null, null, null, null, null, null,
                stRetido == null ? null : new BigDecimal(stRetido), null, null, null, null, null, null));
        return item;
    }

    private static boolean inRange(ItemResult result, String charged) {
        BigDecimal value = new BigDecimal(charged);
        return value.compareTo(result.min().subtract(new BigDecimal("0.03"))) >= 0
                && value.compareTo(result.max().add(new BigDecimal("0.03"))) <= 0;
    }

    /** NF-e 292727 (10/2026), fechaduras do RJ para PE: R$ 11.704,33, ICMS 7%, MVA ajustada 80,15% → SEFAZ-PE cobrou R$ 3.503,21. */
    @Test
    void stOfLocksFromRioDeJaneiro() {
        FiscalXmlDocument document = note("RJ", "11704.33");
        FiscalXmlDocumentItem item = item(document, "FECH-01", "83014000", "6102", "11704.33", "7", "819.30", "0", null);
        FronteiraRule st = rule(FronteiraRuleKind.ST_MVA);
        st.setNcm("83014000");
        st.setMva(new BigDecimal("54")); // MVA original that gives the 80,15% ajustada with 7% → 20,5%

        ItemResult result = FronteiraCalculator.item(document, item, new Context(List.of(interna(null, "20.5"), st), false, null));

        assertThat(result.treatment()).isEqualTo(Treatment.ST);
        assertThat(result.value()).isBetween(new BigDecimal("3503.19"), new BigDecimal("3503.22"));
        assertThat(inRange(result, "3503.21")).isTrue();
        assertThat(result.memoria()).anySatisfy(line -> assertThat(line).contains("80,15%"));
    }

    private static FronteiraRule cargaTecidos() {
        FronteiraRule carga = rule(FronteiraRuleKind.SIMPLES_PERCENTUAL);
        carga.setDescricao("Tecidos e armarinho (indústria)");
        carga.setPercentual(new BigDecimal("5.5"));
        return carga;
    }

    /**
     * Regime normal company credenciada (e-Fisco: ContribuinteRegimeSimples = 0, credenciamento 26 "IND TEC
     * CREDENCIADA", carga líquida 5,5%): the R$ 887,00 note of the extract was charged R$ 48,78.
     */
    @Test
    void credenciamentoCargaLiquidaWhateverTheRegime() {
        FiscalXmlDocument document = note("SP", "887.00");
        FiscalXmlDocumentItem item = item(document, "TEC-01", "52083200", "6102", "887.00", "7", "62.09", "0", null);

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        assertThat(result.treatment()).isEqualTo(Treatment.CARGA_LIQUIDA);
        assertThat(inRange(result, "48.78")).isTrue();
    }

    /**
     * e-Fisco's own memory of NF 473 (RN, R$ 2.400,00, supplier of the Simples, no ICMS destacado):
     * ICMSAntecipadoItemCL = BaseCalculoItemAntecipado 2.400,00 × PercentualCargaLiquida 5,5% = 132,00.
     * The carga líquida takes no credit, so the Simples supplier's credit doesn't matter.
     */
    @Test
    void efiscoMemoryOfNote473() {
        FiscalXmlDocument document = note("RN", "2400.00");
        document.setMetadataVersion(5); // read before the Simples credit was stored
        FiscalXmlDocumentItem item = item(document, "P1", "52083200", "6101", "2400.00", null, null, "0", null);
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, "0", "102", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        assertThat(result.treatment()).isEqualTo(Treatment.CARGA_LIQUIDA);
        assertThat(result.value()).isEqualByComparingTo("132.00");
        assertThat(inRange(result, "132.00")).isTrue();
    }

    /**
     * e-Fisco's own memory of NF 22799 (SC, produto R$ 576,61 + frete R$ 134,29, ICMS destacado R$ 49,77 at 7%):
     * ValorPartidaAntecipado = 576,61 + frete 134,29 = 710,90; ICMSAntecipadoItemCL = 710,90 × 5,5% = 39,0995 → 39,10,
     * the value of the extract. The freight goes into the base; the credit (CFLA 40,36) doesn't count for a carga líquida.
     */
    @Test
    void efiscoMemoryOfNote22799WithFreight() {
        FiscalXmlDocument document = note("SC", "576.61");
        document.setValorFrete(new BigDecimal("134.29"));
        FiscalXmlDocumentItem item = item(document, "P1", "52083200", "6101", "576.61", "7", "49.77", "0", null);

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        assertThat(result.treatment()).isEqualTo(Treatment.CARGA_LIQUIDA);
        assertThat(result.partida()).isEqualByComparingTo("710.90");
        assertThat(result.value()).isEqualByComparingTo("39.10");
        assertThat(inRange(result, "39.10")).isTrue();
        assertThat(result.memoria().get(0)).contains("frete R$ 134,29");
        assertThat(result.variables().get("frete")).isEqualByComparingTo("134.29");
    }

    /**
     * e-Fisco's own memory of NF 973 (RN, R$ 14.018,40, supplier of the Simples, no ICMS destacado, no freight):
     * ICMSAntecipadoItemCL = 14.018,40 × 5,5% = 771,012 → 771,01, the value of the extract.
     */
    @Test
    void efiscoMemoryOfNote973() {
        FiscalXmlDocument document = note("RN", "14018.40");
        FiscalXmlDocumentItem item = item(document, "P1", "52083200", "6101", "14018.40", null, null, "0", null);
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, "0", "102", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        assertThat(result.treatment()).isEqualTo(Treatment.CARGA_LIQUIDA);
        assertThat(result.value()).isEqualByComparingTo("771.01");
        assertThat(inRange(result, "771.01")).isTrue();
    }

    /** e-Fisco's own memory of NF 475 (RN, R$ 13.720,00, Simples supplier): 13.720,00 × 5,5% = 754,60, the value of the extract. */
    @Test
    void efiscoMemoryOfNote475() {
        FiscalXmlDocument document = note("RN", "13720.00");
        FiscalXmlDocumentItem item = item(document, "P1", "52083200", "6101", "13720.00", null, null, "0", null);
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, "0", "102", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        assertThat(result.value()).isEqualByComparingTo("754.60");
        assertThat(inRange(result, "754.60")).isTrue();
    }

    /** Freight of a note with two items goes by product value, unrounded until the end (as e-Fisco keeps it). */
    @Test
    void freightIsSharedByProductValue() {
        FiscalXmlDocument document = note("SC", "300.00");
        document.setValorFrete(new BigDecimal("100.00"));
        FiscalXmlDocumentItem first = item(document, "P1", "52083200", "6101", "100.00", "7", "7.00", "0", null);

        ItemResult result = FronteiraCalculator.item(document, first,
                new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        // frete do item = 100 × 100 ÷ 300 = 33,333...; partida = 133,333...; × 5,5% = 7,3333 → 7,33
        assertThat(result.value()).isEqualByComparingTo("7.33");
    }

    /** Without the credenciamento, a regime normal company doesn't take a carga líquida meant for one. */
    @Test
    void aCargaLiquidaOfAnotherCredenciamentoIsNotUsed() {
        FiscalXmlDocument document = note("CE", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "61091000", "6102", "1000.00", "12", "120.00", "0", null);

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "OUTRO CREDENCIAMENTO"));

        assertThat(result.treatment()).isEqualTo(Treatment.SEM_ENCERRAMENTO);
    }

    @Test
    void regimeNormalWithoutEncerramento() {
        FiscalXmlDocument document = note("CE", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "61091000", "6102", "1000.00", "12", "120.00", "0", null);

        ItemResult result = FronteiraCalculator.item(document, item, new Context(List.of(interna(null, "20.5")), false, null));

        // diferencial = 20,5% − 12% = 8,5%; ICMS = 1.000,00 × 8,5% = 85,00
        assertThat(result.treatment()).isEqualTo(Treatment.SEM_ENCERRAMENTO);
        assertThat(result.value()).isEqualByComparingTo("85.00");
    }

    @Test
    void theAccountantsFormulaWinsAndTheMostSpecificOneIsUsed() {
        FiscalXmlDocument document = note("SP", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "61091000", "6102", "1000.00", "7", "70.00", "0", null);
        FronteiraRule byNcm = rule(FronteiraRuleKind.FORMULA);
        byNcm.setNcm("6109");
        byNcm.setFormula("partida * 10%");
        FronteiraRule byProduct = rule(FronteiraRuleKind.FORMULA);
        byProduct.setCodigoProduto("P1");
        byProduct.setReviewedAt(null);
        byProduct.setFormula("(partida - icms_origem) * 5,5%");

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), byNcm, byProduct), false, null));

        assertThat(result.treatment()).isEqualTo(Treatment.FORMULA);
        assertThat(result.value()).isEqualByComparingTo("51.15"); // (1000 − 70) × 5,5%
        assertThat(result.reviewed()).isFalse();                  // the product's formula is still "a conferir"
        assertThat(result.rulesUsed()).containsExactly(byProduct.getId());
    }

    @Test
    void aFormulaOutOfItsValidityIsNotUsed() {
        FiscalXmlDocument document = note("SP", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "61091000", "6102", "1000.00", "7", "70.00", "0", null);
        FronteiraRule old = rule(FronteiraRuleKind.FORMULA);
        old.setCodigoProduto("P1");
        old.setFormula("partida * 1%");
        old.setValidUntil(LocalDate.of(2026, 9, 30));

        ItemResult result = FronteiraCalculator.item(document, item, new Context(List.of(interna(null, "20.5"), old), false, null));

        assertThat(result.treatment()).isEqualTo(Treatment.SEM_ENCERRAMENTO);
    }

    @Test
    void withoutARateItIsSemRegraNeverCobrouAMais() {
        FiscalXmlDocument document = note("SP", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "61091000", "6102", "1000.00", "7", "70.00", "0", null);

        FronteiraCalculator.NoteResult result = FronteiraCalculator.calculate(document, List.of(item), new Context(List.of(), false, null));

        assertThat(result.semRegra()).isTrue();
        assertThat(result.items().get(0).treatment()).isEqualTo(Treatment.SEM_REGRA);
        assertThat(result.items().get(0).semRegraMotivo()).contains("alíquota interna");
    }

    @Test
    void devolutionAndStAlreadyWithheldHaveNoAntecipacao() {
        FiscalXmlDocument document = note("SP", "2000.00");
        FiscalXmlDocumentItem devolucao = item(document, "P1", "61091000", "6202", "1000.00", "7", "70.00", "0", null);
        FiscalXmlDocumentItem stRetido = item(document, "P2", "83014000", "6403", "1000.00", "7", "70.00", "0", "150.00");
        stRetido.setNumeroItem(2);
        Context context = new Context(List.of(interna(null, "20.5")), false, null);

        FronteiraCalculator.NoteResult result = FronteiraCalculator.calculate(document, new ArrayList<>(List.of(devolucao, stRetido)), context);

        assertThat(result.items()).extracting(ItemResult::treatment).containsExactly(Treatment.SEM_ANTECIPACAO, Treatment.ST_RETIDO);
        assertThat(result.value()).isEqualByComparingTo("0");
    }

    /**
     * The extract charges bonificação and "outras" like a purchase: NF 234489 (SC, CFOP 6910, R$ 115,60) 6,36 and
     * NF 1182625 (SP, CFOP 6949, R$ 2,00) 0,11 - the carga líquida of 5,5%. A remessa (6915, conserto) still is not.
     */
    @Test
    void bonificacaoAndOutrasAreChargedLikeAPurchase() {
        Context context = new Context(List.of(interna(null, "20.5"), cargaTecidos()), false, "TECIDOS E ARMARINHO (INDUSTRIA)");
        FiscalXmlDocument sc = note("SC", "115.60");
        FiscalXmlDocument sp = note("SP", "2.00");

        ItemResult bonificacao = FronteiraCalculator.item(sc, item(sc, "P1", "61091000", "6910", "115.60", "12", "13.87", "0", null), context);
        ItemResult outras = FronteiraCalculator.item(sp, item(sp, "P1", "61091000", "6949", "2.00", "7", "0.14", "0", null), context);
        ItemResult conserto = FronteiraCalculator.item(sp, item(sp, "P1", "61091000", "6915", "2.00", "7", "0.14", "0", null), context);

        assertThat(bonificacao.value()).isEqualByComparingTo("6.36");
        assertThat(outras.value()).isEqualByComparingTo("0.11");
        assertThat(conserto.treatment()).isEqualTo(Treatment.SEM_ANTECIPACAO);
    }

    @Test
    void importedGoodsUseFourPercent() {
        FiscalXmlDocument document = note("SP", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "85171300", "6102", "1000.00", "4", "40.00", "1", null);

        ItemResult result = FronteiraCalculator.item(document, item, new Context(List.of(interna(null, "20.5")), false, null));

        assertThat(result.memoria()).anySatisfy(line -> assertThat(line).contains("Alíquota interestadual: 4,00%"));
    }

    /** A company's own rule beats the global one for the same NCM. */
    @Test
    void theCompanysRateBeatsTheGlobalOne() {
        FiscalXmlDocument document = note("CE", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "22030000", "6102", "1000.00", "12", "120.00", "0", null);
        FronteiraRule global = interna("2203", "23");
        FronteiraRule own = interna("2203", "25");
        own.setCompany(new Company(UUID.randomUUID()));

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(interna(null, "20.5"), global, own), false, null));

        assertThat(result.rulesUsed()).contains(own.getId());
    }

    private static FiscalXmlDocumentItem simplesSupplierItem(FiscalXmlDocument document, String valor, String credito) {
        FiscalXmlDocumentItem item = item(document, "P1", "61091000", "6102", valor, null, null, "0", null);
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, "0", credito == null ? "102" : "101", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, credito == null ? null : new BigDecimal("2.56"),
                credito == null ? null : new BigDecimal(credito)));
        return item;
    }

    /** NF 485 (RN, R$ 7.728,00): a general formula "interna − interestadual" for the regime normal only. */
    @Test
    void aGeneralFormulaReplacesOnlyTheTreatmentItIsFor() {
        FronteiraRule diferencial = rule(FronteiraRuleKind.FORMULA);
        diferencial.setFormula("partida * (interna - inter)");
        diferencial.setAplicaEm("SEM_ENCERRAMENTO");
        FronteiraRule st = rule(FronteiraRuleKind.ST_MVA);
        st.setNcm("83014000");
        st.setMva(new BigDecimal("54"));
        Context context = new Context(List.of(interna(null, "20.5"), diferencial, st), false, null);

        FiscalXmlDocument rn = note("RN", "7728.00");
        ItemResult normal = FronteiraCalculator.item(rn, item(rn, "P1", "61091000", "6102", "7728.00", "12", "927.36", "0", null), context);
        FiscalXmlDocument rj = note("RJ", "11704.33");
        ItemResult withSt = FronteiraCalculator.item(rj, item(rj, "FECH-01", "83014000", "6102", "11704.33", "7", "819.30", "0", null), context);
        ItemResult devolucao = FronteiraCalculator.item(rn, item(rn, "P1", "61091000", "6202", "7728.00", "12", "927.36", "0", null), context);

        assertThat(normal.treatment()).isEqualTo(Treatment.FORMULA);
        assertThat(normal.value()).isEqualByComparingTo("656.88"); // 7.728,00 × (20,5% − 12%)
        assertThat(withSt.treatment()).isEqualTo(Treatment.ST);
        assertThat(devolucao.treatment()).isEqualTo(Treatment.SEM_ANTECIPACAO);
    }

    @Test
    void aFormulaForAnyTreatmentStillLeavesDevolutionAndWithheldStAlone() {
        FronteiraRule everything = rule(FronteiraRuleKind.FORMULA);
        everything.setFormula("partida * 1%");
        Context context = new Context(List.of(interna(null, "20.5"), everything), false, null);
        FiscalXmlDocument document = note("SP", "1000.00");

        ItemResult devolucao = FronteiraCalculator.item(document, item(document, "P1", "61091000", "6202", "1000.00", "7", "70.00", "0", null), context);
        ItemResult retido = FronteiraCalculator.item(document, item(document, "P2", "83014000", "6403", "1000.00", "7", "70.00", "0", "150.00"), context);
        ItemResult compra = FronteiraCalculator.item(document, item(document, "P3", "61091000", "6102", "1000.00", "7", "70.00", "0", null), context);

        assertThat(devolucao.treatment()).isEqualTo(Treatment.SEM_ANTECIPACAO);
        assertThat(retido.treatment()).isEqualTo(Treatment.ST_RETIDO);
        assertThat(compra.treatment()).isEqualTo(Treatment.FORMULA);
    }

    private static FronteiraRule stLocks() {
        FronteiraRule st = rule(FronteiraRuleKind.ST_MVA);
        st.setNcm("61091000");
        st.setMva(new BigDecimal("40"));
        return st;
    }

    /** NF 485 from a Simples supplier with no ICMS destacado: the regime normal diferencial needs no credit. */
    @Test
    void theDiferencialIsTheStandardAndNeedsNoCredit() {
        FiscalXmlDocument document = note("RN", "7728.00");
        document.setMetadataVersion(5);

        ItemResult result = FronteiraCalculator.item(document, simplesSupplierItem(document, "7728.00", null),
                new Context(List.of(interna(null, "20.5")), false, null));

        assertThat(result.treatment()).isEqualTo(Treatment.SEM_ENCERRAMENTO);
        assertThat(result.value()).isEqualByComparingTo("656.88"); // 7.728,00 × (20,5% − 12%)
        // the item's values go back with it - the screen tests a formula on this very item
        assertThat(result.variables()).containsEntry("partida", new BigDecimal("7728.00"));
        assertThat(result.variables().get("inter")).isEqualByComparingTo("0.12");
    }

    /** Under ST, a Simples supplier with no ICMS destacado gets e-Fisco's crédito outorgado: the interstate rate over the product. */
    @Test
    void stFromASimplesSupplierTakesTheCreditoOutorgado() {
        FiscalXmlDocument document = note("RN", "1000.00");

        ItemResult result = FronteiraCalculator.item(document, simplesSupplierItem(document, "1000.00", null),
                new Context(List.of(interna(null, "20.5"), stLocks()), false, null));

        // MVA ajustada = 1,40 × 0,88 ÷ 0,795 − 1 = 54,97%; 1.000 × 1,5497 × 0,205 − crédito 1.000 × 12% = 197,69
        assertThat(result.treatment()).isEqualTo(Treatment.ST);
        assertThat(result.value()).isBetween(new BigDecimal("197.65"), new BigDecimal("197.72"));
        assertThat(result.memoria()).anySatisfy(line -> assertThat(line).contains("Crédito outorgado"));
    }

    private static FronteiraRule nobreakAgregacao() {
        FronteiraRule rule = rule(FronteiraRuleKind.AGREGACAO);
        rule.setNcm("8504");
        rule.setAgregacao(new BigDecimal("20"));
        rule.setRegiaoOrigem(FronteiraCalculator.SUL_SUDESTE);
        rule.setFornecedorSimples(true);
        return rule;
    }

    private static FiscalXmlDocumentItem nobreak(FiscalXmlDocument document) {
        FiscalXmlDocumentItem item = item(document, "11204481", "85044040", "6108", "4261.61", null, null, "0", null);
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, "0", "102", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));
        return item;
    }

    /**
     * e-Fisco's own memory of NF 1810 (MG, nobreak NCM 8504.40.40, supplier of the Simples): "Mercadoria 1061 - ELETR-ELETRON",
     * no carga líquida although the company is credenciada; base = 4.261,61 × CoefAgregacaoSulSudeste para Simples 1,20 =
     * 5.113,93; crédito outorgado = 4.261,61 × 7% = 298,31; ICMS = 5.113,93 × 20,5% − 298,31 = 750,04, the value of the extract.
     */
    @Test
    void efiscoMemoryOfNote1810Agregacao() {
        FiscalXmlDocument document = note("MG", "4261.61");

        ItemResult result = FronteiraCalculator.item(document, nobreak(document),
                new Context(List.of(interna(null, "20.5"), cargaTecidos(), nobreakAgregacao()), false, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        assertThat(result.treatment()).isEqualTo(Treatment.MERCADORIA);
        assertThat(result.value()).isEqualByComparingTo("750.04");
        assertThat(inRange(result, "750.04")).isTrue();
    }

    /**
     * The same nobreak booked by the company as ativo imobilizado (its product fiscal rule: CFOP 1551): only the
     * diferencial - 4.261,61 × 20,5% − 298,31 = 575,32. e-Fisco charged 750,04 as goods for resale: 174,72 more.
     */
    @Test
    void anAtivoByTheProductRuleIsOnlyTheDiferencial() {
        FiscalXmlDocument document = note("MG", "4261.61");
        br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule ativo = new br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule(UUID.randomUUID());
        ativo.setCodigoProduto("11204481");
        ativo.setCfopCorreto("1551");

        ItemResult result = FronteiraCalculator.item(document, nobreak(document),
                new Context(List.of(interna(null, "20.5"), cargaTecidos(), nobreakAgregacao()), false, "TECIDOS E ARMARINHO (INDUSTRIA)",
                        br.com.cshub.taxify.domain.fiscalrule.ProductRules.of(List.of(ativo))));

        assertThat(result.treatment()).isEqualTo(Treatment.USO_CONSUMO);
        assertThat(result.value()).isEqualByComparingTo("575.32");
        assertThat(result.memoria()).anySatisfy(line -> assertThat(line).contains("CFOP de entrada 1551"));
        // base dupla only informs: 4.261,61 ÷ 0,795 × 20,5% − 298,31 = 800,59, an exposure of 225,27
        assertThat(result.memoria()).anySatisfy(line -> assertThat(line).contains("base dupla").contains("R$ 800,59").contains("R$ 225,27"));
    }

    /**
     * NF 29086 (SP, mini prensa térmica NCM 8516.60.00, imported - origin 1 -, Simples supplier): e-Fisco charged 28,06 =
     * 136,19 × 1,20 × 20,5% − 136,19 × 4%. As the ativo the company books it as: 136,19 × 20,5% − 5,45 = 22,47.
     */
    @Test
    void importedGoodsTakeTheFourPercentCredit() {
        FiscalXmlDocument document = note("SP", "136.19");
        FiscalXmlDocumentItem item = item(document, "TW6527", "85166000", "6106", "136.19", null, null, "1", null);
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, "1", "102", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));
        FronteiraRule eletro = nobreakAgregacao();
        eletro.setNcm("8516");
        Context asEfisco = new Context(List.of(interna(null, "20.5"), cargaTecidos(), eletro), false, "TECIDOS E ARMARINHO (INDUSTRIA)");
        br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule ativo = new br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule(UUID.randomUUID());
        ativo.setCodigoProduto("TW6527");
        ativo.setDocumentoFornecedor("11111111000111");
        ativo.setCfopCorreto("1551");
        Context asAtivo = new Context(asEfisco.rules(), false, asEfisco.credenciamento(),
                br.com.cshub.taxify.domain.fiscalrule.ProductRules.of(List.of(ativo)));

        assertThat(FronteiraCalculator.item(document, item, asEfisco).value()).isEqualByComparingTo("28.06");
        assertThat(FronteiraCalculator.item(document, item, asAtivo).value()).isEqualByComparingTo("22.47");
    }

    /** An agregação for the other region (or for non-Simples suppliers) is not used. */
    @Test
    void anAgregacaoOfAnotherRegionIsNotUsed() {
        FiscalXmlDocument document = note("MG", "4261.61");
        FronteiraRule northeast = nobreakAgregacao();
        northeast.setRegiaoOrigem(FronteiraCalculator.DEMAIS_REGIOES);
        FronteiraRule notSimples = nobreakAgregacao();
        notSimples.setFornecedorSimples(false);

        ItemResult result = FronteiraCalculator.item(document, nobreak(document),
                new Context(List.of(interna(null, "20.5"), northeast, notSimples), false, null));

        assertThat(result.treatment()).isEqualTo(Treatment.SEM_ENCERRAMENTO);
        assertThat(result.value()).isEqualByComparingTo("575.32");
    }
}
