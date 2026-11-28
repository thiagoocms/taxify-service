package br.com.cshub.taxify.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I-3 (review of 2026-11-28): two transactions writing the same row of {@code capture_period_counts} (the scheduler and
 * a manual sync creating the same month) and of {@code fiscal_document_sources} (two instances seeing the same known
 * document in the same source) at the same time. With a plain INSERT the second one fails on the unique key and takes
 * its page down; the upserts make both commit, one row each. Against the local MySQL only; fictitious data.
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
class CaptureUpsertConcurrencyIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 9, 0);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CapturePeriodCountRepository counts;
    @Autowired
    private FiscalDocumentRepository documents;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String firmId;
    private String companyId;
    private String documentId;

    @BeforeEach
    void fixture() {
        String url = jdbc.execute((Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(url != null && (url.startsWith("jdbc:mysql://localhost:") || url.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + url);
        Timestamp now = Timestamp.valueOf(NOW);
        firmId = UUID.randomUUID().toString();
        companyId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        String personDocument = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'it', 'Escritorio Concorrencia')",
                firmId, now);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', '11222333000181', 'CNPJ')",
                personDocument, now);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'it', ?, ?, 'Empresa Ficticia Concorrencia', 1)", companyId, now, firmId, personDocument);
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                + " xml_file_path, direction, company_role, status, metadata_version)"
                + " VALUES (?, 0, ?, 'it', 'NFE', '55', 'SIEG', ?, 'xml/ficticio.xml', 'INBOUND', 'RECIPIENT', 'AUTHORIZED', 6)",
                documentId, now, companyId);
    }

    @AfterEach
    void cleanUp() {
        if (companyId == null) {
            return;
        }
        jdbc.update("DELETE FROM fiscal_document_sources WHERE company_id = ?", companyId);
        jdbc.update("DELETE FROM fiscal_documents WHERE company_id = ?", companyId);
        jdbc.update("DELETE FROM capture_period_counts WHERE company_id = ?", companyId);
        String personDocument = jdbc.queryForObject("SELECT document_id FROM companies WHERE id = ?", String.class, companyId);
        jdbc.update("DELETE FROM companies WHERE id = ?", companyId);
        jdbc.update("DELETE FROM documents WHERE id = ?", personDocument);
        jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firmId);
    }

    @Test
    void aPlainInsertOfTheSameMonthFailsOnTheUniqueKey() {
        String insert = "INSERT INTO capture_period_counts (id, company_id, channel, document_type, side, period_month, imported_total,"
                + " created_by, created_date, deleted) VALUES (?, ?, 'SIEG', 'NFE', 'RECEIVED', '2026-11', 0, 'it', ?, 0)";
        jdbc.update(insert, UUID.randomUUID().toString(), companyId, Timestamp.valueOf(NOW));
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID().toString(), companyId, Timestamp.valueOf(NOW)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void twoTransactionsCreatingTheSameMonthBothCommit() throws Exception {
        List<Integer> written = concurrently(
                () -> counts.upsert(UUID.randomUUID().toString(), companyId, "SIEG", "NFE", "RECEIVED", "2026-11",
                        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 28), 40, NOW, 38, null, null, NOW, null, "system", NOW),
                () -> counts.upsert(UUID.randomUUID().toString(), companyId, "SIEG", "NFE", "RECEIVED", "2026-11",
                        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 28), 41, NOW.plusMinutes(1), 41, null, 0,
                        NOW.plusMinutes(1), null, "system", NOW.plusMinutes(1)));

        assertThat(written).hasSize(2);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT provider_total, imported_total, known_gap, created_date"
                + " FROM capture_period_counts WHERE company_id = ?", companyId);
        assertThat(rows).as("one row for the month").hasSize(1);
        assertThat(rows.getFirst()).containsEntry("provider_total", 41).containsEntry("imported_total", 41)
                .containsEntry("known_gap", 0);
    }

    @Test
    void theFirstCloseOfAMonthWins() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            counts.upsert(UUID.randomUUID().toString(), companyId, "SIEG", "NFE", "ISSUED", "2026-10", null, null, 5, NOW, 5,
                    null, null, NOW, NOW, "system", NOW);
            counts.upsert(UUID.randomUUID().toString(), companyId, "SIEG", "NFE", "ISSUED", "2026-10", null, null, 6, NOW, 6,
                    null, null, NOW, NOW.plusDays(1), "system", NOW.plusDays(1));
        });
        assertThat(jdbc.queryForObject("SELECT closed_at FROM capture_period_counts WHERE company_id = ? AND side = 'ISSUED'",
                Timestamp.class, companyId).toLocalDateTime()).isEqualTo(NOW);
    }

    @Test
    void twoTransactionsSeeingTheSameKnownDocumentBothCommit() throws Exception {
        List<Integer> written = concurrently(
                () -> documents.recordSeen(UUID.randomUUID().toString(), documentId, companyId, "SIEG", "PERIOD", NOW.plusHours(2)),
                () -> documents.recordSeen(UUID.randomUUID().toString(), documentId, companyId, "SIEG", "PERIOD", NOW.plusHours(1)));

        assertThat(written).hasSize(2);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT first_seen_at, last_seen_at FROM fiscal_document_sources"
                + " WHERE document_id = ?", documentId);
        assertThat(rows).as("one origin row").hasSize(1);
        assertThat((LocalDateTime) rows.getFirst().get("last_seen_at")).as("last_seen_at only moves forward")
                .isEqualTo(NOW.plusHours(2));
        assertThat((LocalDateTime) rows.getFirst().get("first_seen_at")).isEqualTo(NOW.plusHours(2));
    }

    /** I-D: the purge's mark commits on its own (outside any transaction of the caller), once; the undo takes it back. */
    @Test
    void thePurgeMarkCommitsOnItsOwnAndCanBeTakenBack() {
        assertThat(documents.markXmlPurged(documentId, NOW)).isOne();
        assertThat(documents.markXmlPurged(documentId, NOW.plusHours(1))).as("already marked").isZero();
        assertThat((LocalDateTime) jdbc.queryForObject("SELECT xml_purged_at FROM fiscal_documents WHERE id = ?", LocalDateTime.class,
                documentId)).isEqualTo(NOW);

        assertThat(documents.unmarkXmlPurged(documentId, NOW)).isOne();
        assertThat(jdbc.queryForObject("SELECT xml_purged_at FROM fiscal_documents WHERE id = ?", LocalDateTime.class, documentId))
                .isNull();
    }

    /**
     * {@code first} writes in its transaction and waits; {@code second} starts its own and writes the same key (it waits
     * on the row lock); then {@code first} commits and {@code second} goes on. Both results, or the exception.
     */
    private List<Integer> concurrently(Supplier<Integer> first, Supplier<Integer> second)
            throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> transaction.execute(status -> {
                Integer rows = first.get();
                firstWrote.countDown();
                await(release);
                return rows;
            }));
            assertThat(firstWrote.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> b = pool.submit(() -> transaction.execute(status -> second.get()));
            Thread.sleep(300); // the second transaction is waiting on the row of the first
            release.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
