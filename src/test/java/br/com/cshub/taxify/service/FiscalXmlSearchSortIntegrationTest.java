package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.SortProperties;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The {@code sort=} of the notes screen by the old names (documentType, reviewStatus, issuerCnpj, ownIssue,
 * tipoOperacao and the earlier ones): each one translated runs in the real search query (a property of the goods
 * invoice included); an unknown name is refused before the query. Against the local MySQL only.
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
class FiscalXmlSearchSortIntegrationTest {

    @Autowired
    private FiscalDocumentRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyOldSortNameRunsInTheSearch() {
        String url = jdbc.execute((Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(url != null && url.startsWith("jdbc:mysql://localhost:"), "only against a local MySQL");
        for (String old : FiscalXmlQueryService.LEGACY_SORT.keySet()) {
            Pageable sorted = SortProperties.resolve(PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, old)), GoodsInvoice.class,
                    FiscalXmlQueryService.LEGACY_SORT).orElseThrow();
            assertThatNoException().as(old).isThrownBy(() -> repository.search(UUID.randomUUID(), LocalDateTime.of(2026, 1, 1, 0, 0),
                    LocalDateTime.of(2026, 12, 31, 23, 59), null, null, null, null, null, null, null, "5102",
                    DocumentSourceKey.SIEG, DocumentSourceKey.SEFAZ_DFE, sorted));
        }
    }

    @Test
    void anUnknownSortNameIsRefused() {
        assertThat(SortProperties.resolve(PageRequest.of(0, 5, Sort.by("naoExiste")), GoodsInvoice.class,
                FiscalXmlQueryService.LEGACY_SORT)).isEmpty();
    }
}
