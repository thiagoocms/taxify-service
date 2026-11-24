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

    /** Simples company credenciada (art. 363-A at 5,5%): the R$ 887,00 note of the extract was charged R$ 48,78. */
    @Test
    void simplesPercentageOverTheStartingValue() {
        FiscalXmlDocument document = note("SP", "887.00");
        FiscalXmlDocumentItem item = item(document, "TEC-01", "52083200", "6102", "887.00", "7", "62.09", "0", null);
        FronteiraRule simples = rule(FronteiraRuleKind.SIMPLES_PERCENTUAL);
        simples.setDescricao("Tecidos e armarinho (indústria)");
        simples.setPercentual(new BigDecimal("5.5"));

        ItemResult result = FronteiraCalculator.item(document, item,
                new Context(List.of(simples), true, "TECIDOS E ARMARINHO (INDUSTRIA)"));

        assertThat(result.treatment()).isEqualTo(Treatment.SIMPLES);
        assertThat(inRange(result, "48.78")).isTrue();
    }

    @Test
    void regimeNormalWithoutEncerramento() {
        FiscalXmlDocument document = note("CE", "1000.00");
        FiscalXmlDocumentItem item = item(document, "P1", "61091000", "6102", "1000.00", "12", "120.00", "0", null);

        ItemResult result = FronteiraCalculator.item(document, item, new Context(List.of(interna(null, "20.5")), false, null));

        // base = (1000 − 120) ÷ (1 − 0,205) = 1.106,92; ICMS = 1.106,92 × 0,205 − 120 = 106,92
        assertThat(result.treatment()).isEqualTo(Treatment.SEM_ENCERRAMENTO);
        assertThat(result.value()).isEqualByComparingTo("106.92");
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
}
