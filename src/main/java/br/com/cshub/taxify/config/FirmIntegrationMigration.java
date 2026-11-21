package br.com.cshub.taxify.config;

import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.FirmIntegrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-off move of the SIEG / Acessórias credentials from their old columns on
 * {@code accounting_firms} into {@code firm_integrations}. Runs on every startup but only
 * creates a row the firm doesn't have yet, so it's idempotent and never overwrites a key
 * changed through the new screen.
 * <p>
 * The old columns are deliberately left in place (Hibernate's ddl-auto=update doesn't drop
 * them and neither do we): rolling back to an image from before this change keeps working.
 * On a fresh database they don't exist - the query fails and the migration just skips.
 */
@Component
public class FirmIntegrationMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirmIntegrationMigration.class);

    private final JdbcTemplate jdbcTemplate;
    private final FirmIntegrationRepository integrationRepository;
    private final AccountingFirmRepository accountingFirmRepository;
    private final TransactionTemplate transactionTemplate;

    public FirmIntegrationMigration(JdbcTemplate jdbcTemplate, FirmIntegrationRepository integrationRepository,
                                    AccountingFirmRepository accountingFirmRepository, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.integrationRepository = integrationRepository;
        this.accountingFirmRepository = accountingFirmRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, sieg_api_key, acessorias_api_token, acessorias_last_sync_at FROM accounting_firms");
        } catch (DataAccessException e) {
            log.debug("Legacy integration columns not found - nothing to migrate ({})", e.getMessage());
            return;
        }

        int migrated = 0;
        for (Map<String, Object> row : rows) {
            UUID firmId = UUID.fromString(String.valueOf(row.get("id")));
            String siegKey = asString(row.get("sieg_api_key"));
            String acessoriasToken = asString(row.get("acessorias_api_token"));
            LocalDateTime acessoriasLastSync = asDateTime(row.get("acessorias_last_sync_at"));
            try {
                Integer count = transactionTemplate.execute(status ->
                        migrate(firmId, IntegrationType.SIEG, siegKey, null)
                                + migrate(firmId, IntegrationType.ACESSORIAS, acessoriasToken, acessoriasLastSync));
                migrated += count == null ? 0 : count;
            } catch (RuntimeException e) {
                log.warn("Could not migrate integrations of firm {}", firmId, e);
            }
        }
        if (migrated > 0) {
            log.info("Migrated {} legacy SIEG/Acessórias credentials into firm_integrations", migrated);
        }
    }

    private int migrate(UUID firmId, IntegrationType type, String apiKey, LocalDateTime lastSyncAt) {
        if (apiKey == null || apiKey.isBlank()) {
            return 0;
        }
        if (integrationRepository.findFirstByAccountingFirm_IdAndTypeAndDeletedIsFalse(firmId, type).isPresent()) {
            return 0;
        }
        return accountingFirmRepository.findById(firmId).map(firm -> {
            FirmIntegration integration = new FirmIntegration(firm, type);
            integration.setApiKey(apiKey.strip());
            integration.setLastSyncAt(lastSyncAt);
            integrationRepository.save(integration);
            return 1;
        }).orElse(0);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDateTime asDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return null;
    }
}
