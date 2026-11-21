package br.com.cshub.taxify.service.job;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.dto.integration.SyncJobStatusDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.SyncStatus;
import br.com.cshub.taxify.repository.FirmIntegrationRepository;
import br.com.cshub.taxify.service.FirmIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationJobRunnerTest {

    record Result(int created) {
    }

    private final FirmIntegrationService firmIntegrationService = mock(FirmIntegrationService.class);
    private final FirmIntegrationRepository repository = mock(FirmIntegrationRepository.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private IntegrationJobRunner runner;
    private UUID firmId;
    private FirmIntegration integration;

    @BeforeEach
    void setUp() {
        // SyncTaskExecutor runs the job inline, so the test sees its outcome right away
        runner = new IntegrationJobRunner(firmIntegrationService, repository, new SyncTaskExecutor(), scheduler, objectMapper);
        firmId = UUID.randomUUID();
        integration = new FirmIntegration(new AccountingFirm(firmId), IntegrationType.ACESSORIAS);
        integration.setId(UUID.randomUUID());
        when(firmIntegrationService.getOrCreate(firmId, IntegrationType.ACESSORIAS)).thenReturn(integration);
        when(firmIntegrationService.find(firmId, IntegrationType.ACESSORIAS)).thenReturn(Optional.of(integration));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));
    }

    @Test
    void claimedJobRunsReportsProgressAndStoresTheResult() {
        when(repository.claimSync(eq(integration.getId()), any(), any())).thenReturn(1);
        AtomicInteger ran = new AtomicInteger();

        runner.start(firmId, IntegrationType.ACESSORIAS, Result.class, progress -> {
            progress.total(2);
            progress.advance();
            progress.advance();
            ran.incrementAndGet();
            return new Result(7);
        });

        assertThat(ran).hasValue(1);
        verify(repository).completeSync(eq(integration.getId()), eq(SyncStatus.SUCCEEDED), eq("{\"created\":7}"), isNull(), any());
    }

    @Test
    void jobAlreadyRunningIsNotStartedAgain() {
        when(repository.claimSync(eq(integration.getId()), any(), any())).thenReturn(0);
        AtomicInteger ran = new AtomicInteger();

        runner.start(firmId, IntegrationType.ACESSORIAS, Result.class, progress -> {
            ran.incrementAndGet();
            return new Result(1);
        });

        assertThat(ran).hasValue(0);
        verify(repository, never()).completeSync(any(), any(), any(), any(), any());
    }

    @Test
    void failureIsRecordedWithItsMessageAndKeepsThePreviousResult() {
        when(repository.claimSync(eq(integration.getId()), any(), any())).thenReturn(1);

        runner.start(firmId, IntegrationType.ACESSORIAS, Result.class, progress -> {
            throw new IllegalStateException("token recusado");
        });

        verify(repository).completeSync(eq(integration.getId()), eq(SyncStatus.FAILED), isNull(), eq("token recusado"), any());
    }

    @Test
    void statusDeserializesTheStoredResult() {
        integration.setSyncStatus(SyncStatus.SUCCEEDED);
        integration.setSyncProcessed(3);
        integration.setSyncTotal(3);
        integration.setSyncResult("{\"created\":3}");

        SyncJobStatusDTO<Result> status = runner.status(firmId, IntegrationType.ACESSORIAS, Result.class);

        assertThat(status.running()).isFalse();
        assertThat(status.result()).isEqualTo(new Result(3));
        assertThat(status.processed()).isEqualTo(3);
    }

    @Test
    void runningJobWithoutHeartbeatIsReportedAsFailed() {
        integration.setSyncStatus(SyncStatus.RUNNING);
        integration.setSyncHeartbeatAt(java.time.LocalDateTime.now().minusMinutes(10));

        SyncJobStatusDTO<Result> status = runner.status(firmId, IntegrationType.ACESSORIAS, Result.class);

        assertThat(status.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(status.error()).isEqualTo(IntegrationJobRunner.ABANDONED_MESSAGE);

        integration.setSyncHeartbeatAt(java.time.LocalDateTime.now());
        assertThat(runner.status(firmId, IntegrationType.ACESSORIAS, Result.class).running()).isTrue();
    }

    @Test
    void neverRunJobHasNoStatus() {
        when(firmIntegrationService.find(firmId, IntegrationType.SIEG)).thenReturn(Optional.empty());

        SyncJobStatusDTO<Result> status = runner.status(firmId, IntegrationType.SIEG, Result.class);

        assertThat(status.status()).isNull();
        assertThat(status.result()).isNull();
    }
}
