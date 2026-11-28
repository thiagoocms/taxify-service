package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO.OtherUnit;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO.UnitPrice;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProductRuleSuggestionServiceTest {

    private final FiscalDocumentItemRepository itemRepository = mock(FiscalDocumentItemRepository.class);
    private final ProductFiscalRuleRepository ruleRepository = mock(ProductFiscalRuleRepository.class);
    private final CompanyValidation companyValidation = mock(CompanyValidation.class);
    private final ProductRuleSuggestionService service = new ProductRuleSuggestionService(itemRepository, ruleRepository,
            companyValidation, mock(MessageService.class));
    private final UUID companyId = UUID.randomUUID();

    @Test
    void suggestsFromTheReceivedNotesTheMostFrequentValueOfEachFieldWithTheEntryCfop() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findReceivedClassificationsByProduct(companyId)).thenReturn(List.of(
                row("P1", "Refrigerante", "22021000", "5405", "0", "60", null, null, "0300700", "04", 30),
                row("P1", "Refrigerante 2L", "22021000", "5102", "0", "00", null, new BigDecimal("18.0000"), "0300700", "04", 10),
                row("P2", "Agua", "22011000", "5102", "0", "00", null, new BigDecimal("18.0000"), null, "01", 5),
                row("P3", "Tem regra", "1", "5102", "0", "00", null, null, null, "01", 50)));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule("P3", null)));

        List<ProductRuleSuggestionDTO> result = service.suggest(companyId.toString(), true, null, null);

        assertThat(result).extracting(ProductRuleSuggestionDTO::codigoProduto).containsExactly("P1", "P2");
        ProductRuleSuggestionDTO p1 = result.getFirst();
        assertThat(p1.descricaoProduto()).isEqualTo("Refrigerante");
        assertThat(p1.ocorrencias()).isEqualTo(40);
        assertThat(p1.campos().get("cfopCorreto").valor()).isEqualTo("1403"); // supplier 5405 -> entry 1403 (5102 -> 1102 is the other 25%)
        assertThat(p1.cfopFornecedor().valor()).isEqualTo("5405");
        assertThat(p1.campos().get("cfopCorreto").percentual()).isEqualByComparingTo("75.0");
        assertThat(p1.campos().get("cstIcms").valor()).isEqualTo("60");
        // the internal rate only shows on 10 of the items - 100% of those that have it
        assertThat(p1.campos().get("aliquotaIcms").valor()).isEqualTo("18");
        assertThat(p1.campos().get("aliquotaIcms").percentual()).isEqualByComparingTo("100.0");
        assertThat(p1.campos().get("cstPis").valor()).isEqualTo("04");
        assertThat(p1.campos()).doesNotContainKey("csosn");

        List<ProductRuleSuggestionDTO> all = service.suggest(companyId.toString(), false, null, null);
        assertThat(all).extracting(ProductRuleSuggestionDTO::codigoProduto, ProductRuleSuggestionDTO::possuiRegra)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("P3", true),
                        org.assertj.core.groups.Tuple.tuple("P1", false),
                        org.assertj.core.groups.Tuple.tuple("P2", false));
    }

    @Test
    void theRuleFormAsksForOneProductOnly() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findReceivedClassificationsByProduct(companyId)).thenReturn(List.of(
                row("P1", "Refrigerante", "22021000", "6102", "0", "00", null, null, null, "01", 30),
                row("P2", "Agua", "22011000", "5102", "0", "00", null, null, null, "01", 5)));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

        List<ProductRuleSuggestionDTO> result = service.suggest(companyId.toString(), false, " P2 ", null);

        assertThat(result).extracting(ProductRuleSuggestionDTO::codigoProduto).containsExactly("P2");
        assertThat(result.getFirst().campos().get("cfopCorreto").valor()).isEqualTo("1102");
    }

    @Test
    void entryCfopOfTheSupplierExitCfop() {
        assertThat(EntryCfops.fromSupplier("5102")).isEqualTo("1102");
        assertThat(EntryCfops.fromSupplier("6102")).isEqualTo("2102");
        assertThat(EntryCfops.fromSupplier("7101")).isEqualTo("3101");
        assertThat(EntryCfops.fromSupplier("5405")).isEqualTo("1403");
        assertThat(EntryCfops.fromSupplier("6656")).isEqualTo("2653");
        assertThat(EntryCfops.fromSupplier("1202")).isEqualTo("1202"); // already an entry CFOP
        assertThat(EntryCfops.fromSupplier(" 5949 ")).isEqualTo("1949");
        assertThat(EntryCfops.fromSupplier("51A2")).isEqualTo("51A2");
    }

    private static final String SUPPLIER = "11222333000181";

    @Test
    void theSameCodeFromTwoSuppliersIsTwoProductsAndTheSupplierRuleCoversOnlyItsOwn() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findReceivedClassificationsByProduct(companyId)).thenReturn(List.of(
                row("001", "Parafuso", "73181500", "6102", "0", "00", null, null, null, "01", "11222333000181", "Fornecedor A", 8),
                row("001", "Cimento", "25232910", "5102", "0", "00", null, null, null, "01", "99888777000166", "Fornecedor B", 5)));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule("001", "11.222.333/0001-81")));

        List<ProductRuleSuggestionDTO> all = service.suggest(companyId.toString(), false, null, null);
        assertThat(all).extracting(ProductRuleSuggestionDTO::documentoFornecedor, ProductRuleSuggestionDTO::possuiRegra)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("11222333000181", true),
                        org.assertj.core.groups.Tuple.tuple("99888777000166", false));
        assertThat(all.getFirst().nomeFornecedor()).isEqualTo("Fornecedor A");
        // an interstate purchase (6102) is suggested as the internal one: the rule adapts the first digit to each note
        assertThat(all.getFirst().campos().get("cfopCorreto").valor()).isEqualTo("1102");

        assertThat(service.suggest(companyId.toString(), true, null, null))
                .extracting(ProductRuleSuggestionDTO::descricaoProduto).containsExactly("Cimento");
        assertThat(service.suggest(companyId.toString(), false, "001", "99.888.777/0001-66"))
                .extracting(ProductRuleSuggestionDTO::descricaoProduto).containsExactly("Cimento");
    }

    // ---- spec PU: the commercial unit price of each suggestion (CT-01, 06, 07, 08, 13, 14) ----

    private static final String SUPPLIER_B = "11444777000161";

    @Test
    void ct07EachSupplierHasItsOwnPriceAndCt08TheSupplierIsJoinedByItsDigits() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findReceivedClassificationsByProduct(companyId)).thenReturn(List.of(
                row("PRD-001", "Parafuso", "73181500", "5102", "0", "00", null, null, null, "01", SUPPLIER, "Fornecedor A", 4),
                row("PRD-001", "Parafuso", "73181500", "5102", "0", "00", null, null, null, "01", SUPPLIER_B, "Fornecedor B", 1)));
        when(itemRepository.findReceivedUnitPricesByProduct(companyId)).thenReturn(List.of(
                // CT-01 in A, split between the masked and the bare document (CT-08) and between "un" and "UN"
                price("PRD-001", "11.222.333/0001-81", "un", 1, "10", "120", "12", "12"),
                price("PRD-001", SUPPLIER, "UN", 2, "90", "1350", "14", "15.5"),
                price("PRD-001", SUPPLIER_B, "UN", 1, "4", "80", "20", "20"),
                price("OTHER", SUPPLIER, "UN", 1, "1", "1", "1", "1")));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

        List<ProductRuleSuggestionDTO> result = service.suggest(companyId.toString(), false, null, null);

        assertThat(result).extracting(ProductRuleSuggestionDTO::documentoFornecedor).containsExactly(SUPPLIER, SUPPLIER_B);
        assertThat(result.get(0).unitPrice()).isEqualTo(new UnitPrice(new BigDecimal("14.7000"), new BigDecimal("12.0000"),
                new BigDecimal("15.5000"), "UN", 3, new BigDecimal("100.0000"), List.of()));
        assertThat(result.get(1).unitPrice()).isEqualTo(new UnitPrice(new BigDecimal("20.0000"), new BigDecimal("20.0000"),
                new BigDecimal("20.0000"), "UN", 1, new BigDecimal("4.0000"), List.of()));
        // PU-RN-08: the price is not a rule field
        assertThat(result.get(0).campos()).doesNotContainKeys("unitPrice", "average");
    }

    @Test
    void ct03TheOtherUnitsGoApart() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findReceivedClassificationsByProduct(companyId)).thenReturn(List.<Object[]>of(
                row("PRD-001", "Parafuso", "73181500", "5102", "0", "00", null, null, null, "01", 4)));
        when(itemRepository.findReceivedUnitPricesByProduct(companyId)).thenReturn(List.of(
                price("PRD-001", SUPPLIER, "un", 1, "2", "10", "5", "5"),
                price("PRD-001", SUPPLIER, "UN ", 2, "3", "16.5", "5", "6.5"),
                price("PRD-001", SUPPLIER, "CX", 1, "1", "60", "60", "60")));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

        UnitPrice unitPrice = service.suggest(companyId.toString(), false, null, null).getFirst().unitPrice();

        assertThat(unitPrice.unit()).isEqualTo("UN");
        assertThat(unitPrice.average()).isEqualTo(new BigDecimal("5.3000"));
        assertThat(unitPrice.itemsConsidered()).isEqualTo(3);
        assertThat(unitPrice.otherUnits()).containsExactly(new OtherUnit("CX", 1));
    }

    @Test
    void ct06WithoutAnyPricedItemThePriceIsNullAndTheItemsStillCount() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findReceivedClassificationsByProduct(companyId)).thenReturn(List.<Object[]>of(
                row("PRD-001", "Parafuso", "73181500", "5102", "0", "00", null, null, null, "01", 2)));
        when(itemRepository.findReceivedUnitPricesByProduct(companyId)).thenReturn(List.of());
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

        ProductRuleSuggestionDTO suggestion = service.suggest(companyId.toString(), false, null, null).getFirst();

        assertThat(suggestion.unitPrice()).isNull();
        assertThat(suggestion.ocorrencias()).isEqualTo(2);
    }

    @Test
    void ct13TwoQueriesOnTheItemsWhateverTheNumberOfProducts() {
        for (int products : new int[]{3, 300}) {
            FiscalDocumentItemRepository items = mock(FiscalDocumentItemRepository.class);
            ProductRuleSuggestionService counted = new ProductRuleSuggestionService(items, ruleRepository, companyValidation,
                    mock(MessageService.class));
            when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
            List<Object[]> classifications = new ArrayList<>();
            List<Object[]> prices = new ArrayList<>();
            for (int i = 0; i < products; i++) {
                classifications.add(row("P" + i, "Produto " + i, "1", "5102", "0", "00", null, null, null, "01", 1));
                prices.add(price("P" + i, SUPPLIER, "UN", 1, "1", "2", "2", "2"));
            }
            when(items.findReceivedClassificationsByProduct(companyId)).thenReturn(classifications);
            when(items.findReceivedUnitPricesByProduct(companyId)).thenReturn(prices);
            when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

            List<ProductRuleSuggestionDTO> result = counted.suggest(companyId.toString(), false, null, null);

            assertThat(result).hasSize(products).allSatisfy(suggestion -> assertThat(suggestion.unitPrice()).isNotNull());
            verify(items, times(1)).findReceivedClassificationsByProduct(companyId);
            verify(items, times(1)).findReceivedUnitPricesByProduct(companyId);
            verifyNoMoreInteractions(items);
        }
    }

    @Test
    void ct14TheRuleFormGetsThePriceOfItsProductAndNoPriceQueryWithoutProducts() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findReceivedClassificationsByProduct(companyId)).thenReturn(List.of(
                row("PRD-001", "Parafuso", "73181500", "5102", "0", "00", null, null, null, "01", 3),
                row("PRD-002", "Porca", "73181600", "5102", "0", "00", null, null, null, "01", 9)));
        when(itemRepository.findReceivedUnitPricesByProduct(companyId)).thenReturn(List.of(
                price("PRD-001", SUPPLIER, "UN", 3, "100", "1470", "12", "15.5"),
                price("PRD-002", SUPPLIER, "UN", 9, "9", "9", "1", "1")));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

        List<ProductRuleSuggestionDTO> result = service.suggest(companyId.toString(), false, "PRD-001", "11222333000181");

        assertThat(result).singleElement().satisfies(suggestion ->
                assertThat(suggestion.unitPrice().average()).isEqualTo(new BigDecimal("14.7000")));

        FiscalDocumentItemRepository empty = mock(FiscalDocumentItemRepository.class);
        when(empty.findReceivedClassificationsByProduct(companyId)).thenReturn(List.of());
        assertThat(new ProductRuleSuggestionService(empty, ruleRepository, companyValidation, mock(MessageService.class))
                .suggest(companyId.toString(), false, null, null)).isEmpty();
        verify(empty, never()).findReceivedUnitPricesByProduct(any());
    }

    /** A row of findReceivedUnitPricesByProduct: code, supplier, unit, count, Σq, Σ(q×p), min, max. */
    private static Object[] price(String codigo, String supplier, String unit, long count, String quantity, String amount,
                                  String min, String max) {
        return new Object[]{codigo, supplier, unit, count, new BigDecimal(quantity).setScale(4),
                new BigDecimal(amount).setScale(8), new BigDecimal(min).setScale(4), new BigDecimal(max).setScale(4)};
    }

    private static ProductFiscalRule rule(String codigo, String supplier) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode(codigo);
        rule.setCorrectCfop("1102");
        rule.setSupplierDocument(supplier == null ? null : supplier.replaceAll("\\D", ""));
        return rule;
    }

    /** codigo, descricao, ncm, cfop, origem, cst, csosn, pICMS interno, cest, cst PIS + the rest null, supplier, count. */
    private static Object[] row(String codigo, String descricao, String ncm, String cfop, String origem, String cst,
                                String csosn, BigDecimal aliquotaIcms, String cest, String cstPis, long count) {
        return row(codigo, descricao, ncm, cfop, origem, cst, csosn, aliquotaIcms, cest, cstPis, SUPPLIER, "Fornecedor", count);
    }

    private static Object[] row(String codigo, String descricao, String ncm, String cfop, String origem, String cst,
                                String csosn, BigDecimal aliquotaIcms, String cest, String cstPis, String supplier,
                                String supplierName, long count) {
        return new Object[]{codigo, descricao, ncm, cfop, origem, cst, csosn, aliquotaIcms, cest,
                null, null, cstPis, null, null, null, null, null, supplier, supplierName, count};
    }
}
