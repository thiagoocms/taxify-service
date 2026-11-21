package br.com.cshub.taxify.config;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.FirmIntegrationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirmIntegrationMigrationTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final FirmIntegrationRepository integrationRepository = mock(FirmIntegrationRepository.class);
    private final AccountingFirmRepository accountingFirmRepository = mock(AccountingFirmRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final FirmIntegrationMigration migration =
            new FirmIntegrationMigration(jdbcTemplate, integrationRepository, accountingFirmRepository, transactionTemplate);

    @SuppressWarnings("unchecked")
    private void runCallbacksInline() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> ((TransactionCallback<Integer>) inv.getArgument(0)).doInTransaction(null));
    }

    @Test
    void copiesLegacyKeysOnceAndKeepsExistingRows() {
        runCallbacksInline();
        UUID firmId = UUID.randomUUID();
        LocalDateTime lastSync = LocalDateTime.of(2026, 11, 19, 3, 0);
        Map<String, Object> row = new HashMap<>();
        row.put("id", firmId.toString());
        row.put("sieg_api_key", " sieg-key ");
        row.put("acessorias_api_token", "aces-token");
        row.put("acessorias_last_sync_at", Timestamp.valueOf(lastSync));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));
        when(accountingFirmRepository.findById(firmId)).thenReturn(Optional.of(new AccountingFirm(firmId)));
        // SIEG was already set through the new screen - must not be overwritten
        when(integrationRepository.findFirstByAccountingFirm_IdAndTypeAndDeletedIsFalse(firmId, IntegrationType.SIEG))
                .thenReturn(Optional.of(new FirmIntegration()));
        when(integrationRepository.findFirstByAccountingFirm_IdAndTypeAndDeletedIsFalse(firmId, IntegrationType.ACESSORIAS))
                .thenReturn(Optional.empty());

        migration.run(null);

        ArgumentCaptor<FirmIntegration> saved = ArgumentCaptor.forClass(FirmIntegration.class);
        verify(integrationRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(IntegrationType.ACESSORIAS);
        assertThat(saved.getValue().getApiKey()).isEqualTo("aces-token");
        assertThat(saved.getValue().getLastSyncAt()).isEqualTo(lastSync);
    }

    @Test
    void freshDatabaseWithoutLegacyColumnsIsSkipped() {
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new DataRetrievalFailureException("Unknown column"));

        migration.run(null);

        verify(integrationRepository, never()).save(any());
    }

    @Test
    void blankKeysAreNotMigrated() {
        runCallbacksInline();
        UUID firmId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("id", firmId.toString());
        row.put("sieg_api_key", "  ");
        row.put("acessorias_api_token", null);
        row.put("acessorias_last_sync_at", null);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        migration.run(null);

        verify(integrationRepository, never()).save(any());
        verify(integrationRepository, never()).findFirstByAccountingFirm_IdAndTypeAndDeletedIsFalse(eq(firmId), any());
    }
}
