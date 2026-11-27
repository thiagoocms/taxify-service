package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.enumerated.RecoveryTaskSource;
import br.com.cshub.taxify.enumerated.RecoveryTaskStatus;

import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.firmaccess.RecoveryTask;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FirmCertificateRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.RecoveryTaskRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA-02 (decisão do Tony): recuperação dos meses bloqueados - CT1-22, o limite de 3 meses, retomada depois de restart,
 * idempotência, falha que não conclui e novo bloqueio no meio. As tarefas ficam num "banco" em memória com a mesma
 * chave única (escopo, fonte, início) da V66.
 */
class BlockedPeriodRecoveryServiceTest {

    private final List<RecoveryTask> store = new CopyOnWriteArrayList<>();
    private final RecoveryTaskRepository tasks = mock(RecoveryTaskRepository.class);
    private final FirmSubscriptionRepository subscriptions = mock(FirmSubscriptionRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final SubscriptionRepository plans = mock(SubscriptionRepository.class);
    private final FirmCertificateRepository certificates = mock(FirmCertificateRepository.class);
    private final FirmEventService events = mock(FirmEventService.class);
    private final SaasProperties properties = new SaasProperties();
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2027, 1, 4, 10, 0));
    private final FiscalXmlProvider provider = mock(FiscalXmlProvider.class);

    private final AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
    private final Company company = new Company(UUID.randomUUID());
    private FirmSubscription subscription;
    private BlockedPeriodRecoveryService service;

    @BeforeEach
    void setUp() {
        company.setAccountingFirm(firm);
        subscription = new FirmSubscription();
        subscription.setAccountingFirmId(firm.getId());
        subscription.setStatus(FirmSubscriptionStatus.ACTIVE);
        when(subscriptions.findForUpdate(firm.getId())).thenReturn(Optional.of(subscription));
        when(companies.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firm.getId())).thenReturn(List.of(company));
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION));
        Subscription planSubscription = new Subscription(UUID.randomUUID());
        planSubscription.setPlan(plan);
        when(plans.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                eq(company.getId()), any(), any())).thenReturn(Optional.of(planSubscription));
        lenient().when(certificates.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())).thenReturn(Optional.of(new FirmCertificate()));
        inMemoryTasks();
        service = newService();
    }

    /** A fresh instance over the same stored tasks - what a restart is. */
    private BlockedPeriodRecoveryService newService() {
        return new BlockedPeriodRecoveryService(tasks, subscriptions, companies, plans, certificates, events, properties, clock,
                mock(PlatformTransactionManager.class));
    }

    private void inMemoryTasks() {
        when(tasks.save(any(RecoveryTask.class))).thenAnswer(invocation -> {
            RecoveryTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                // the unique key (scope, source, start) of V66
                assertThat(store).noneMatch(other -> other.getScopeId().equals(task.getScopeId())
                        && other.getSource() == task.getSource() && other.getPeriodStart().equals(task.getPeriodStart()));
                ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
                store.add(task);
            }
            return task;
        });
        when(tasks.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.findByScopeIdAndSourceAndPeriodStart(any(), any(), any())).thenAnswer(invocation -> store.stream()
                .filter(t -> t.getScopeId().equals(invocation.getArgument(0)) && t.getSource() == invocation.getArgument(1)
                        && t.getPeriodStart().equals(invocation.getArgument(2))).findFirst());
        when(tasks.findFirstByCompanyIdAndStatusAndSourceInAndDeletedIsFalseOrderByPeriodStartAscSourceAsc(any(), any(), any())).thenAnswer(invocation -> {
            Collection<RecoveryTaskSource> sources = invocation.getArgument(2);
            return store.stream()
                    .filter(t -> invocation.getArgument(0).equals(t.getCompanyId()) && t.getStatus() == invocation.getArgument(1)
                            && sources.contains(t.getSource()))
                    .min(Comparator.comparing(RecoveryTask::getPeriodStart).thenComparing(t -> t.getSource().name()));
        });
        when(tasks.findByAccountingFirmIdAndStatusAndSourceAndDeletedIsFalse(any(), any(), any())).thenAnswer(invocation -> store.stream()
                .filter(t -> t.getAccountingFirmId().equals(invocation.getArgument(0)) && t.getStatus() == invocation.getArgument(1)
                        && t.getSource() == invocation.getArgument(2)).toList());
        when(tasks.findByStatusAndSourceInAndDeletedIsFalse(any(), any())).thenAnswer(invocation -> {
            Collection<RecoveryTaskSource> sources = invocation.getArgument(1);
            return store.stream().filter(t -> t.getStatus() == invocation.getArgument(0) && sources.contains(t.getSource())).toList();
        });
        when(tasks.findByAccountingFirmIdAndStatusAndDeletedIsFalse(any(), any())).thenAnswer(invocation -> store.stream()
                .filter(t -> t.getAccountingFirmId().equals(invocation.getArgument(0)) && t.getStatus() == invocation.getArgument(1)).toList());
        when(tasks.countByAccountingFirmIdAndStatusAndDeletedIsFalse(any(), any())).thenAnswer(invocation -> store.stream()
                .filter(t -> t.getAccountingFirmId().equals(invocation.getArgument(0)) && t.getStatus() == invocation.getArgument(1)).count());
        when(tasks.findByAccountingFirmIdAndPeriodStartBetweenAndDeletedIsFalse(any(), any(), any())).thenAnswer(invocation -> store.stream()
                .filter(t -> t.getAccountingFirmId().equals(invocation.getArgument(0))
                        && !t.getPeriodStart().isBefore(invocation.getArgument(1)) && !t.getPeriodStart().isAfter(invocation.getArgument(2)))
                .toList());
    }

    private void schedule(LocalDate blockedFrom) {
        service.schedule(subscription, blockedFrom, "csadmin");
    }

    private List<String> pending() {
        return store.stream().filter(RecoveryTask::isPending)
                .map(t -> t.getSource() + "@" + YearMonth.from(t.getPeriodStart())).sorted().toList();
    }

    private static FiscalXmlProvider.RecoveryPage page(boolean last) {
        return new FiscalXmlProvider.RecoveryPage(last ? 3 : 50, 1, last);
    }

    /** CT1-22: bloqueio 25/12, reativação 04/01 → dezembro, dos dois lados, página a página, e o evento no fim. */
    @Test
    void blockedOnDecember25ReactivatedOnJanuary4RecoversDecember() {
        schedule(LocalDate.of(2026, 12, 25));
        assertThat(pending()).containsExactly("SIEG_NFE_ISSUED@2026-12", "SIEG_NFE_RECEIVED@2026-12");
        assertThat(subscription.getRecoveryFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(subscription.getRecoveryUntil()).isEqualTo(LocalDate.of(2027, 1, 4));
        assertThat(service.pendingMonths(firm.getId())).containsExactly("2026-12");
        LocalDate dec1 = LocalDate.of(2026, 12, 1);
        LocalDate dec31 = LocalDate.of(2026, 12, 31);
        when(provider.recoverPage(company.getId(), FiscalXmlSide.ISSUED, dec1, dec31, 0)).thenReturn(Optional.of(page(false)));
        when(provider.recoverPage(company.getId(), FiscalXmlSide.ISSUED, dec1, dec31, 1)).thenReturn(Optional.of(page(true)));
        when(provider.recoverPage(company.getId(), FiscalXmlSide.RECEIVED, dec1, dec31, 0)).thenReturn(Optional.of(page(true)));

        // one step per turn of the company: 2 pages issued, 1 page received
        assertThat(service.runNextSiegStep(provider, company)).isTrue();
        assertThat(service.runNextSiegStep(provider, company)).isTrue();
        verify(events, never()).recordFields(eq(firm.getId()), eq(FirmEventType.RECOVERY_DONE), any(), any(), any(), any());
        assertThat(service.runNextSiegStep(provider, company)).isTrue();
        assertThat(service.runNextSiegStep(provider, company)).isFalse();

        assertThat(pending()).isEmpty();
        assertThat(subscription.getRecoveryFrom()).isNull();
        ArgumentCaptor<Map<String, Object>> done = ArgumentCaptor.captor();
        verify(events).recordFields(eq(firm.getId()), eq(FirmEventType.RECOVERY_DONE), any(), done.capture(), any(), eq("system"));
        assertThat(done.getValue()).containsEntry("SIEG_NFE_ISSUED", List.of("2026-12")).containsEntry("SIEG_NFE_RECEIVED", List.of("2026-12"));
        // each page asked exactly once - nothing asked twice, nothing to duplicate
        verify(provider, times(3)).recoverPage(any(), any(), any(), any(), anyInt());
    }

    /** Bloqueio de 5 meses com o limite padrão de 3: só out, nov e dez; e-Fisco out e nov; NFS-e out e nov. */
    @Test
    void aFiveMonthBlockRecoversOnlyTheThreeMostRecentMonths() {
        schedule(LocalDate.of(2026, 8, 1));

        // CA1-28 / CT1-46: the trail lists the months beyond the limit
        ArgumentCaptor<Map<String, Object>> scheduled = ArgumentCaptor.captor();
        verify(events).recordFields(eq(firm.getId()), eq(FirmEventType.RECOVERY_SCHEDULED), any(), scheduled.capture(), any(), eq("csadmin"));
        assertThat(scheduled.getValue()).containsEntry("not_recovered", List.of("2026-08", "2026-09"))
                .containsEntry("sieg_nfe", List.of("2026-10", "2026-11", "2026-12"));

        assertThat(pending()).containsExactly(
                "EFISCO@2026-10", "EFISCO@2026-11",
                "SIEG_NFE_ISSUED@2026-10", "SIEG_NFE_ISSUED@2026-11", "SIEG_NFE_ISSUED@2026-12",
                "SIEG_NFE_RECEIVED@2026-10", "SIEG_NFE_RECEIVED@2026-11", "SIEG_NFE_RECEIVED@2026-12",
                "SIEG_NFSE@2026-10", "SIEG_NFSE@2026-11");
        assertThat(service.pendingMonths(firm.getId())).containsExactly("2026-10", "2026-11", "2026-12");
    }

    /** Restart no meio de um mês: a nova instância continua da página gravada; agendar de novo não duplica nada. */
    @Test
    void afterARestartTheRecoveryGoesOnFromTheStoredPage() {
        schedule(LocalDate.of(2026, 12, 25));
        when(provider.recoverPage(any(), eq(FiscalXmlSide.ISSUED), any(), any(), eq(0))).thenReturn(Optional.of(page(false)));
        service.runNextSiegStep(provider, company);

        BlockedPeriodRecoveryService restarted = newService();
        assertThat(restarted.schedule(subscription, LocalDate.of(2026, 12, 25), "csadmin")).isZero();
        assertThat(store).hasSize(2);
        when(provider.recoverPage(any(), eq(FiscalXmlSide.ISSUED), any(), any(), eq(1))).thenReturn(Optional.of(page(true)));
        restarted.runNextSiegStep(provider, company);

        verify(provider, times(1)).recoverPage(any(), eq(FiscalXmlSide.ISSUED), any(), any(), eq(0));
        verify(provider, times(1)).recoverPage(any(), eq(FiscalXmlSide.ISSUED), any(), any(), eq(1));
        assertThat(pending()).containsExactly("SIEG_NFE_RECEIVED@2026-12");
    }

    /** Falha não conclui: o passo fica pendente e a mesma página é pedida na próxima rodada. */
    @Test
    void aFailureIsRetriedOnTheNextTurn() {
        schedule(LocalDate.of(2026, 12, 25));
        when(provider.recoverPage(any(), eq(FiscalXmlSide.ISSUED), any(), any(), eq(0)))
                .thenThrow(new IllegalStateException("SIEG fora para destinatario@exemplo.test"))
                .thenReturn(Optional.of(page(true)));

        service.runNextSiegStep(provider, company);
        RecoveryTask issued = store.stream().filter(t -> t.getSource() == RecoveryTaskSource.SIEG_NFE_ISSUED).findFirst().orElseThrow();
        assertThat(issued.isPending()).isTrue();
        assertThat(issued.getAttempts()).isEqualTo(1);
        assertThat(issued.getLastError()).isEqualTo("IllegalStateException");

        service.runNextSiegStep(provider, company);
        assertThat(issued.isPending()).isFalse();
    }

    /**
     * Novo bloqueio antes de concluir: os agendadores param (o portão - os pedaços ficam pendentes, não são pulados) e
     * a próxima reativação junta o período novo ao que faltava.
     */
    @Test
    void aNewBlockPausesAndTheNextReactivationJoinsThePeriods() {
        schedule(LocalDate.of(2026, 12, 25));

        // blocked again: the orphan sweep never skips a blocked firm's pieces
        RoutineGate blocked = new RoutineGate("sieg", firmId -> false);
        service.skipSiegOrphans(List.of(), blocked);
        assertThat(pending()).hasSize(2);

        // reactivated on 05/03: February is added; December still waits
        clock.set(LocalDateTime.of(2027, 3, 5, 9, 0));
        schedule(LocalDate.of(2027, 2, 10));
        assertThat(pending()).containsExactly("SIEG_NFE_ISSUED@2026-12", "SIEG_NFE_ISSUED@2027-02",
                "SIEG_NFE_RECEIVED@2026-12", "SIEG_NFE_RECEIVED@2027-02");
        assertThat(subscription.getRecoveryFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(subscription.getRecoveryUntil()).isEqualTo(LocalDate.of(2027, 3, 5));
    }

    /** Empresa que a captura não atende mais (sem o serviço, inativa) é pulada, para o escritório concluir. */
    @Test
    void piecesOfACompanyNoLongerServedAreSkipped() {
        schedule(LocalDate.of(2026, 12, 25));

        service.skipSiegOrphans(List.of(), new RoutineGate("sieg", firmId -> true));

        assertThat(pending()).isEmpty();
        assertThat(store).allMatch(t -> t.getStatus() == RecoveryTaskStatus.SKIPPED);
        assertThat(subscription.getRecoveryFrom()).isNull();
    }

    /** e-Fisco: os meses numa chamada; OK conclui, ERRO tenta no dia seguinte. */
    @Test
    void theEfiscoMonthsGoInOneCallAndAnErrorWaitsForTheNextDay() {
        schedule(LocalDate.of(2026, 8, 1));
        List<List<YearMonth>> calls = new ArrayList<>();

        service.runEfisco(firm.getId(), months -> {
            calls.add(months);
            return "ERRO";
        });
        assertThat(pending()).contains("EFISCO@2026-10", "EFISCO@2026-11");

        service.runEfisco(firm.getId(), months -> {
            calls.add(months);
            return "OK";
        });
        assertThat(pending()).noneMatch(item -> item.startsWith("EFISCO"));
        assertThat(calls).containsExactly(List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11)),
                List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11)));
    }

    /** Sem certificado do contador na reativação, nenhum mês do e-Fisco é agendado. */
    @Test
    void noEfiscoWithoutTheAccountantsCertificate() {
        when(certificates.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())).thenReturn(Optional.empty());
        schedule(LocalDate.of(2026, 8, 1));
        assertThat(pending()).noneMatch(item -> item.startsWith("EFISCO"));
    }

    /** CA1-28: nothing to fetch (no SIEG company, no certificate) - the event still records the months not recovered. */
    @Test
    void theEventGoesEvenWhenNothingIsFetched() {
        when(companies.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firm.getId())).thenReturn(List.of());
        when(certificates.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())).thenReturn(Optional.empty());

        schedule(LocalDate.of(2026, 8, 1));

        assertThat(store).isEmpty();
        assertThat(subscription.getRecoveryFrom()).isNull();
        ArgumentCaptor<Map<String, Object>> scheduled = ArgumentCaptor.captor();
        verify(events).recordFields(eq(firm.getId()), eq(FirmEventType.RECOVERY_SCHEDULED), any(), scheduled.capture(), any(), any());
        assertThat(scheduled.getValue()).containsEntry("not_recovered", List.of("2026-08", "2026-09"));
    }

    /** Review 1: after MAX_ATTEMPTS failures in a row the piece is closed (SKIPPED, with the reason), not retried forever. */
    @Test
    void afterTenFailuresThePieceIsGivenUp() {
        schedule(LocalDate.of(2026, 12, 25));
        when(provider.recoverPage(any(), any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("fora"));

        for (int i = 0; i < BlockedPeriodRecoveryService.MAX_ATTEMPTS; i++) {
            service.runNextSiegStep(provider, company);
        }

        RecoveryTask issued = store.stream().filter(t -> t.getSource() == RecoveryTaskSource.SIEG_NFE_ISSUED).findFirst().orElseThrow();
        assertThat(issued.getStatus()).isEqualTo(RecoveryTaskStatus.SKIPPED);
        assertThat(issued.getLastError()).startsWith("gave up after 10 failures");
        assertThat(store.stream().filter(t -> t.getSource() == RecoveryTaskSource.SIEG_NFE_RECEIVED).findFirst().orElseThrow().isPending())
                .isTrue();
    }

    /** Review 2: pieces of an earlier cycle that fell behind the limit are closed on the next reactivation, and listed. */
    @Test
    void stalePiecesBeyondTheLimitAreClosed() {
        schedule(LocalDate.of(2026, 10, 10)); // reactivated on 04/01: October, November, December
        clock.set(LocalDateTime.of(2027, 5, 3, 9, 0));
        schedule(LocalDate.of(2027, 4, 20)); // reactivated on 03/05: limit from February

        assertThat(pending()).containsExactly("SIEG_NFE_ISSUED@2027-04", "SIEG_NFE_RECEIVED@2027-04");
        assertThat(store.stream().filter(t -> YearMonth.from(t.getPeriodStart()).isBefore(YearMonth.of(2027, 2))))
                .allMatch(t -> t.getStatus() == RecoveryTaskStatus.SKIPPED && "beyond the recovery limit".equals(t.getLastError()));
        ArgumentCaptor<Map<String, Object>> scheduled = ArgumentCaptor.captor();
        verify(events, times(2)).recordFields(eq(firm.getId()), eq(FirmEventType.RECOVERY_SCHEDULED), any(), scheduled.capture(), any(), any());
        assertThat(scheduled.getAllValues().getLast()).containsEntry("not_recovered", List.of("2026-10", "2026-11", "2026-12"));
    }

    /** Review 3: a month over MAX_PAGES is closed as "truncated", and the completion event says so. */
    @Test
    void aMonthOverThePageLimitIsTruncated() {
        schedule(LocalDate.of(2026, 12, 25));
        store.forEach(t -> t.setPageCursor(BlockedPeriodRecoveryService.MAX_PAGES - 1));
        when(provider.recoverPage(any(), any(), any(), any(), anyInt())).thenReturn(Optional.of(page(false)));

        service.runNextSiegStep(provider, company);
        service.runNextSiegStep(provider, company);

        assertThat(store).allMatch(t -> t.getStatus() == RecoveryTaskStatus.DONE && "truncated".equals(t.getLastError()));
        ArgumentCaptor<Map<String, Object>> done = ArgumentCaptor.captor();
        verify(events).recordFields(eq(firm.getId()), eq(FirmEventType.RECOVERY_DONE), any(), done.capture(), any(), any());
        assertThat(done.getValue()).containsEntry("truncated", List.of("SIEG_NFE_ISSUED@2026-12", "SIEG_NFE_RECEIVED@2026-12"));
    }

    /** Nada fora da janela normal: nada agendado, nenhum evento. */
    @Test
    void aBlockInsideTheCurrentMonthSchedulesNothing() {
        schedule(LocalDate.of(2027, 1, 2));
        assertThat(store).isEmpty();
        assertThat(subscription.getRecoveryFrom()).isNull();
        verify(events, never()).recordFields(any(), any(), any(), any(), any(), any());
    }
}
