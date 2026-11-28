package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO.OtherUnit;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO.UnitPrice;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec PU (unit price of the rule suggestions) against the local MySQL: the price query
 * ({@code findReceivedUnitPricesByProduct}) keeps the cut of the suggestions (CT-05: canceled, deleted and issued notes
 * are out), the sum of the groups of one supplier and by the normalized unit (CT-03), the empty unit (CT-09), the
 * items without a price or quantity (CT-06), and the number of statements whatever the number of products (CT-13).
 * Fictitious data.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
class ProductRuleSuggestionUnitPriceIntegrationTest {

    private static final String SUPPLIER_A = "11222333000181";
    private static final String SUPPLIER_B = "11444777000161";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FiscalDocumentItemRepository items;
    @Autowired
    private ProductFiscalRuleRepository rules;
    @Autowired
    private MessageService messageService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final CompanyValidation companyValidation = mock(CompanyValidation.class);
    private ProductRuleSuggestionService service;
    private String firmId;
    private String companyId;
    private String personDocument;

    @BeforeEach
    void fixture() {
        String url = jdbc.execute((Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(url != null && url.startsWith("jdbc:mysql://localhost:"), "only against a local MySQL");
        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 8, 0));
        firmId = UUID.randomUUID().toString();
        companyId = UUID.randomUUID().toString();
        personDocument = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'it', 'Escritorio PU')",
                firmId, now);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', '99888777000166', 'CNPJ')",
                personDocument, now);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'it', ?, ?, 'Empresa Ficticia PU', 1)", companyId, now, firmId, personDocument);
        when(companyValidation.checkExist(UUID.fromString(companyId))).thenReturn(new Company(UUID.fromString(companyId)));
        service = new ProductRuleSuggestionService(items, rules, companyValidation, messageService);
    }

    @AfterEach
    void cleanUp() {
        if (companyId == null) {
            return;
        }
        jdbc.update("DELETE i FROM fiscal_document_items i JOIN fiscal_documents d ON d.id = i.document_id WHERE d.company_id = ?",
                companyId);
        jdbc.update("DELETE FROM fiscal_documents WHERE company_id = ?", companyId);
        jdbc.update("DELETE FROM companies WHERE id = ?", companyId);
        jdbc.update("DELETE FROM documents WHERE id = ?", personDocument);
        jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firmId);
    }

    @Test
    void ct01Ct05Ct08ThePriceKeepsTheCutOfTheSuggestionsAndJoinsTheSupplierByItsDigits() {
        // CT-01 in A, over two notes (the column keeps only the digits: the masked document of CT-08 is in the unit test)
        item(note(SUPPLIER_A, "RECIPIENT", "AUTHORIZED", false), "PRD-001", "UN", "10", "12");
        String noteA = note(SUPPLIER_A, "RECIPIENT", "AUTHORIZED", false);
        item(noteA, "PRD-001", "UN", "30", "14");
        item(noteA, "PRD-001", "UN", "60", "15.5");
        // CT-05: out of the price - canceled, deleted, issued by the company
        item(note(SUPPLIER_A, "RECIPIENT", "CANCELED", false), "PRD-001", "UN", "50", "99");
        item(note(SUPPLIER_A, "RECIPIENT", "AUTHORIZED", true), "PRD-001", "UN", "5", "1");
        item(note(SUPPLIER_A, "ISSUER", "AUTHORIZED", false), "PRD-001", "UN", "5", "200");
        // CT-07: the same code from B
        item(note(SUPPLIER_B, "RECIPIENT", "AUTHORIZED", false), "PRD-001", "UN", "4", "20");

        List<ProductRuleSuggestionDTO> result = service.suggest(companyId, false, null, null);

        assertThat(result).extracting(ProductRuleSuggestionDTO::documentoFornecedor).containsExactly(SUPPLIER_A, SUPPLIER_B);
        assertThat(result.get(0).ocorrencias()).isEqualTo(3);
        assertThat(result.get(0).unitPrice()).isEqualTo(new UnitPrice(new BigDecimal("14.7000"), new BigDecimal("12.0000"),
                new BigDecimal("15.5000"), "UN", 3, new BigDecimal("100.0000"), List.of()));
        assertThat(result.get(1).unitPrice().average()).isEqualTo(new BigDecimal("20.0000"));
        assertThat(result.get(1).unitPrice().itemsConsidered()).isEqualTo(1);

        // CT-14: the rule form asks for one product and supplier
        assertThat(service.suggest(companyId, false, "PRD-001", SUPPLIER_A)).singleElement()
                .satisfies(suggestion -> assertThat(suggestion.unitPrice()).isEqualTo(result.get(0).unitPrice()));
    }

    @Test
    void ct03Ct06Ct09UnitsAreNormalizedAndItemsWithoutPriceOrQuantityStillCountAsOccurrences() {
        String note = note(SUPPLIER_A, "RECIPIENT", "AUTHORIZED", false);
        item(note, "PRD-001", "un", "2", "5");
        item(note, "PRD-001", "UN ", "2", "5");
        item(note, "PRD-001", "UN", "1", "6.5");
        item(note, "PRD-001", "CX", "1", "60");
        item(note, "PRD-006", "UN", "0", "10");
        item(note, "PRD-006", "UN", "2", null);
        item(note, "PRD-009", "", "1", "2");
        item(note, "PRD-009", null, "1", "4");

        List<ProductRuleSuggestionDTO> result = service.suggest(companyId, false, null, null);

        ProductRuleSuggestionDTO ct03 = find(result, "PRD-001");
        assertThat(ct03.unitPrice().unit()).isEqualTo("UN");
        assertThat(ct03.unitPrice().average()).isEqualTo(new BigDecimal("5.3000"));
        assertThat(ct03.unitPrice().min()).isEqualTo(new BigDecimal("5.0000"));
        assertThat(ct03.unitPrice().max()).isEqualTo(new BigDecimal("6.5000"));
        assertThat(ct03.unitPrice().itemsConsidered()).isEqualTo(3);
        assertThat(ct03.unitPrice().otherUnits()).containsExactly(new OtherUnit("CX", 1));

        ProductRuleSuggestionDTO ct06 = find(result, "PRD-006");
        assertThat(ct06.unitPrice()).isNull();
        assertThat(ct06.ocorrencias()).isEqualTo(2);

        ProductRuleSuggestionDTO ct09 = find(result, "PRD-009");
        assertThat(ct09.unitPrice().unit()).isEqualTo("—");
        assertThat(ct09.unitPrice().average()).isEqualTo(new BigDecimal("3.0000"));
    }

    @Test
    void ct13TheSameNumberOfStatementsWith3And300Products() {
        String note = note(SUPPLIER_A, "RECIPIENT", "AUTHORIZED", false);
        insertProducts(note, 0, 3);
        long with3 = statementsOfOneSuggestion(3);
        insertProducts(note, 3, 300);
        long with300 = statementsOfOneSuggestion(300);

        // the classifications, the prices and the company's rules - the company check is mocked here
        assertThat(with3).isEqualTo(3);
        assertThat(with300).isEqualTo(with3);
    }

    private long statementsOfOneSuggestion(int expectedProducts) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            List<ProductRuleSuggestionDTO> result = service.suggest(companyId, false, null, null);
            assertThat(result).hasSize(expectedProducts).allSatisfy(suggestion -> assertThat(suggestion.unitPrice()).isNotNull());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    private void insertProducts(String note, int from, int to) {
        List<Object[]> batch = new ArrayList<>();
        for (int i = from; i < to; i++) {
            batch.add(new Object[]{UUID.randomUUID().toString(), note, i + 1, "P-" + i});
        }
        jdbc.batchUpdate("INSERT INTO fiscal_document_items (id, document_id, item_number, product_code, cfop, total_amount,"
                + " commercial_unit, commercial_quantity, commercial_unit_price) VALUES (?, ?, ?, ?, '5102', 1.00, 'UN', 1, 1)", batch);
    }

    private static ProductRuleSuggestionDTO find(List<ProductRuleSuggestionDTO> result, String code) {
        return result.stream().filter(suggestion -> code.equals(suggestion.codigoProduto())).findFirst().orElseThrow();
    }

    private int itemNumber;

    private String note(String supplier, String companyRole, String status, boolean deleted) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                + " xml_file_path, direction, company_role, status, metadata_version, issue_date, issuer_document,"
                + " issuer_document_type, issuer_name, header_destination_type)"
                + " VALUES (?, ?, ?, 'it', 'NFE', '55', 'SIEG', ?, 'xml/ficticio.xml', 'INBOUND', ?, ?, 6, ?, ?, 'CNPJ',"
                + " 'Fornecedor Exemplo Ltda', '1')",
                id, deleted, Timestamp.valueOf(LocalDateTime.of(2026, 10, 10, 10, 0)), companyId, companyRole, status,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 10, 10, 0)), supplier);
        return id;
    }

    private void item(String note, String code, String unit, String quantity, String unitPrice) {
        jdbc.update("INSERT INTO fiscal_document_items (id, document_id, item_number, product_code, cfop, total_amount,"
                + " commercial_unit, commercial_quantity, commercial_unit_price) VALUES (?, ?, ?, ?, '5102', 1.00, ?, ?, ?)",
                UUID.randomUUID().toString(), note, ++itemNumber, code, unit, new BigDecimal(quantity),
                unitPrice == null ? null : new BigDecimal(unitPrice));
    }
}
