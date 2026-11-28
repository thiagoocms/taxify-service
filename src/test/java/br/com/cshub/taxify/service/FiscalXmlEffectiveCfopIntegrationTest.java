package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FD-RN-57, review of 2026-11-28 (I-A): a note rectified with the rule's CFOP 1556 whose rule was edited afterwards
 * (now 1949). The notes screen shows the stored effective CFOP (the rectified file's, 1556) and its CFOP filter finds
 * the note by 1556 - the same value in both, whatever the rule says now; 1949 and the issued 1102 find nothing. The two
 * queries of the screen (the CFOPs of the page and the search) against the local MySQL; fictitious data.
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
class FiscalXmlEffectiveCfopIntegrationTest {

    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 10, 10, 10, 0);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FiscalDocumentRepository documents;
    @Autowired
    private FiscalDocumentItemRepository items;

    private String firmId;
    private String companyId;
    private String documentId;

    @BeforeEach
    void fixture() {
        String url = jdbc.execute((Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(url != null && url.startsWith("jdbc:mysql://localhost:"), "only against a local MySQL");
        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 8, 0));
        firmId = UUID.randomUUID().toString();
        companyId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        String personDocument = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'it', 'Escritorio CFOP')",
                firmId, now);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', '11222333000181', 'CNPJ')",
                personDocument, now);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'it', ?, ?, 'Empresa Ficticia CFOP', 1)", companyId, now, firmId, personDocument);
        // rectified on 2026-11-05 with the rule's 1556 (the item keeps the issued 1102 and the effective 1556)
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                + " xml_file_path, direction, company_role, status, metadata_version, issue_date, issuer_document,"
                + " issuer_document_type, header_destination_type, rectified_file_path, last_rectification_checked_at)"
                + " VALUES (?, 0, ?, 'it', 'NFE', '55', 'SIEG', ?, 'xml/ficticio.xml', 'INBOUND', 'RECIPIENT', 'AUTHORIZED', 6, ?,"
                + " '12345678000195', 'CNPJ', '1', 'rect/ficticio.xml', '2026-11-05 10:00:00')",
                documentId, now, companyId, Timestamp.valueOf(ISSUED));
        jdbc.update("INSERT INTO fiscal_document_items (id, document_id, item_number, product_code, cfop, effective_cfop, total_amount)"
                + " VALUES (?, ?, 1, 'P-1', '1102', '1556', 100.00)", UUID.randomUUID().toString(), documentId);
        // the rule was edited after the rectification: 1949 now
        jdbc.update("INSERT INTO product_fiscal_rules (id, deleted, created_date, created_by, company_id, codigo_produto,"
                + " documento_fornecedor, cfop_correto, last_modified_date) VALUES (?, 0, ?, 'it', ?, 'P-1', '12345678000195', '1949',"
                + " '2026-11-20 10:00:00')", UUID.randomUUID().toString(), now, companyId);
    }

    @AfterEach
    void cleanUp() {
        if (companyId == null) {
            return;
        }
        jdbc.update("DELETE FROM product_fiscal_rules WHERE company_id = ?", companyId);
        jdbc.update("DELETE FROM fiscal_document_items WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM fiscal_documents WHERE company_id = ?", companyId);
        String personDocument = jdbc.queryForObject("SELECT document_id FROM companies WHERE id = ?", String.class, companyId);
        jdbc.update("DELETE FROM companies WHERE id = ?", companyId);
        jdbc.update("DELETE FROM documents WHERE id = ?", personDocument);
        jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firmId);
    }

    private List<FiscalDocument> searchByCfop(String cfop) {
        return documents.search(UUID.fromString(companyId), ISSUED.minusDays(1), ISSUED.plusDays(1), null, null, null, null, null,
                null, null, cfop, DocumentSourceKey.SIEG, DocumentSourceKey.SEFAZ_DFE, PageRequest.of(0, 10)).getContent();
    }

    @Test
    @Transactional
    void theListShowsAndTheFilterFindsTheSameRectifiedCfop() {
        List<Object[]> shown = items.findDistinctEffectiveCfopsByDocumentIds(List.of(UUID.fromString(documentId)));
        assertThat(shown).singleElement().satisfies(row -> assertThat(row[1]).isEqualTo("1556"));

        assertThat(searchByCfop("1556")).extracting(FiscalDocument::getId).containsExactly(UUID.fromString(documentId));
        assertThat(searchByCfop("1949")).as("the rule's CFOP of now is not the rectified file's").isEmpty();
        assertThat(searchByCfop("1102")).as("the issued CFOP is not the one in effect").isEmpty();
    }
}
