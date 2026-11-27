package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.enumerated.RecoveryTaskSource;
import br.com.cshub.taxify.enumerated.RecoveryTaskStatus;

import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.domain.firmaccess.RecoveryTask;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FirmCertificateRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.RecoveryTaskRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Recuperação dos meses bloqueados (decisão do Tony sobre o QA-02). Quando o escritório sai de BLOCKED, SUSPENDED ou
 * CANCELED para acesso total, as rotinas de captura que trabalham por janela de datas buscam o que ficou de fora:
 * SIEG NF-e (emitidas e recebidas), SIEG NFS-e e e-Fisco - ver {@link RecoveryPlan}. A SEFAZ e a NFS-e Nacional
 * continuam do NSU e não entram aqui.
 * <ul>
 *   <li>Persistente: uma {@link RecoveryTask} por empresa (ou escritório), fonte e mês, com chave única - sobrevive a
 *       restart, roda uma vez só, e a importação ignora notas já guardadas.</li>
 *   <li>Ritmo: um passo por empresa por rodada do agendador (uma página da SIEG), no rodízio que já respeita o limite
 *       de requisições da SIEG.</li>
 *   <li>Falha não conclui: o passo fica PENDING e tenta na rodada seguinte.</li>
 *   <li>Escritório bloqueado de novo antes de concluir: os agendadores já não o processam (o portão), e a próxima
 *       reativação junta o período novo ao que faltava.</li>
 *   <li>Concluído o escritório: evento RECOVERY_DONE com os meses; o log só leva ids.</li>
 * </ul>
 */
@Service
public class BlockedPeriodRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(BlockedPeriodRecoveryService.class);
    static final Set<RecoveryTaskSource> SIEG_SOURCES =
            EnumSet.of(RecoveryTaskSource.SIEG_NFE_RECEIVED, RecoveryTaskSource.SIEG_NFE_ISSUED, RecoveryTaskSource.SIEG_NFSE);
    /** Proteção: um mês com mais páginas que isso é encerrado como "truncated" (10 mil notas de um lado num mês). */
    static final int MAX_PAGES = 200;
    /** Falhas seguidas até o pedaço ser encerrado (SKIPPED) - não fica "recuperando" para sempre. */
    static final int MAX_ATTEMPTS = 10;
    static final String TRUNCATED = "truncated";

    private final RecoveryTaskRepository taskRepository;
    private final FirmSubscriptionRepository subscriptionRepository;
    private final CompanyRepository companyRepository;
    private final SubscriptionRepository planSubscriptionRepository;
    private final FirmCertificateRepository certificateRepository;
    private final FirmEventService eventService;
    private final SaasProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public BlockedPeriodRecoveryService(RecoveryTaskRepository taskRepository, FirmSubscriptionRepository subscriptionRepository,
                                        CompanyRepository companyRepository, SubscriptionRepository planSubscriptionRepository,
                                        FirmCertificateRepository certificateRepository, FirmEventService eventService,
                                        SaasProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.companyRepository = companyRepository;
        this.planSubscriptionRepository = planSubscriptionRepository;
        this.certificateRepository = certificateRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ scheduling (on the reactivation)

    /**
     * O escritório voltou a ter acesso total depois de um bloqueio desde {@code blockedFrom}: agenda o que recuperar.
     * Roda na transação de quem reativou (ação do admin da plataforma; na fase 2, o pagamento).
     *
     * @return quantos pedaços novos ou reabertos
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int schedule(FirmSubscription subscription, LocalDate blockedFrom, String actor) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        UUID firmId = subscription.getAccountingFirmId();
        RecoveryPlan.Plan plan = RecoveryPlan.plan(blockedFrom, today, properties.getUnblockRecoveryMonths());
        // pieces still pending from an earlier cycle that fell behind the limit: closed, and listed as not recovered
        YearMonth earliest = RecoveryPlan.earliestRecoverable(today, properties.getUnblockRecoveryMonths());
        java.util.TreeSet<YearMonth> notRecovered = new java.util.TreeSet<>(plan.notRecoveredMonths());
        for (RecoveryTask stale : taskRepository.findByAccountingFirmIdAndStatusAndDeletedIsFalse(firmId, RecoveryTaskStatus.PENDING)) {
            if (YearMonth.from(stale.getPeriodStart()).isBefore(earliest)) {
                stale.finish(RecoveryTaskStatus.SKIPPED, "beyond the recovery limit", now);
                taskRepository.save(stale);
                notRecovered.add(YearMonth.from(stale.getPeriodStart()));
            }
        }
        int added = 0;
        if (!plan.nfeMonths().isEmpty() || !plan.nfseWindows().isEmpty()) {
            for (Company company : siegCompanies(firmId, today)) {
                for (YearMonth month : plan.nfeMonths()) {
                    added += upsert(firmId, company.getId(), RecoveryTaskSource.SIEG_NFE_RECEIVED, month.atDay(1), month.atEndOfMonth());
                    added += upsert(firmId, company.getId(), RecoveryTaskSource.SIEG_NFE_ISSUED, month.atDay(1), month.atEndOfMonth());
                }
                for (RecoveryPlan.Window window : plan.nfseWindows()) {
                    added += upsert(firmId, company.getId(), RecoveryTaskSource.SIEG_NFSE, window.start(), window.end());
                }
            }
        }
        if (!plan.efiscoMonths().isEmpty() && certificateRepository.findByAccountingFirm_IdAndDeletedIsFalse(firmId).isPresent()) {
            for (YearMonth month : plan.efiscoMonths()) {
                added += upsert(firmId, null, RecoveryTaskSource.EFISCO, month.atDay(1), month.atEndOfMonth());
            }
        }
        boolean pending = taskRepository.countByAccountingFirmIdAndStatusAndDeletedIsFalse(firmId, RecoveryTaskStatus.PENDING) > 0;
        if (added > 0 || pending) {
            LocalDate from = plan.isEmpty() ? blockedFrom : firstDay(plan);
            subscription.addRecovery(from, today);
            subscriptionRepository.save(subscription);
        } else if (subscription.getRecoveryFrom() != null) {
            subscription.clearRecovery();
            subscriptionRepository.save(subscription);
        }
        // transparency (F1-RN-10): the event goes even when the whole block is beyond the limit
        if (added > 0 || pending || !notRecovered.isEmpty()) {
            Map<String, Object> fields = summary(plan);
            fields.put("not_recovered", notRecovered.stream().map(YearMonth::toString).toList());
            eventService.recordFields(firmId, FirmEventType.RECOVERY_SCHEDULED, null, fields, null, actor);
            log.info("recovery.scheduled firm={} tasks={} notRecoveredMonths={}", firmId, added, notRecovered.size());
        }
        return added;
    }

    private int upsert(UUID firmId, UUID companyId, RecoveryTaskSource source, LocalDate start, LocalDate end) {
        UUID scope = companyId == null ? firmId : companyId;
        Optional<RecoveryTask> existing = taskRepository.findByScopeIdAndSourceAndPeriodStart(scope, source, start);
        if (existing.isPresent()) {
            RecoveryTask task = existing.get();
            boolean changed = false;
            if (end.isAfter(task.getPeriodEnd())) {
                task.setPeriodEnd(end);
                changed = true;
            }
            if (!task.isPending()) {
                task.reopen();
                changed = true;
            }
            if (changed) {
                taskRepository.save(task);
                return 1;
            }
            return 0;
        }
        taskRepository.save(new RecoveryTask(firmId, companyId, source, start, end));
        return 1;
    }

    /** As empresas do escritório que a captura da SIEG atende hoje (ativas, com a importação de XML e a SIEG no plano). */
    private List<Company> siegCompanies(UUID firmId, LocalDate today) {
        return companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmId).stream()
                .filter(company -> !Boolean.FALSE.equals(company.getActive()))
                .filter(company -> planSubscriptionRepository
                        .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                                company.getId(), today, today)
                        .map(Subscription::getPlan)
                        .map(plan -> PlanServiceEnum.importsFrom(plan.getServices(), PlanServiceEnum.SIEG_INTEGRATION))
                        .orElse(false))
                .toList();
    }

    // ------------------------------------------------------------------ SIEG (FiscalXmlSyncScheduler)

    /**
     * Um passo da recuperação da empresa, na rodada dela: uma página de NF-e, ou uma janela de NFS-e. Falha fica
     * PENDING (tenta na próxima rodada).
     *
     * @return true quando havia algo a fazer
     */
    public boolean runNextSiegStep(FiscalXmlProvider provider, Company company) {
        Optional<RecoveryTask> next = taskRepository.findFirstByCompanyIdAndStatusAndSourceInAndDeletedIsFalseOrderByPeriodStartAscSourceAsc(
                company.getId(), RecoveryTaskStatus.PENDING, SIEG_SOURCES);
        if (next.isEmpty()) {
            return false;
        }
        RecoveryTask task = next.get();
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            switch (task.getSource()) {
                case SIEG_NFE_RECEIVED, SIEG_NFE_ISSUED -> {
                    FiscalXmlSide side = task.getSource() == RecoveryTaskSource.SIEG_NFE_ISSUED ? FiscalXmlSide.ISSUED : FiscalXmlSide.RECEIVED;
                    Optional<FiscalXmlProvider.RecoveryPage> page = provider.recoverPage(company.getId(), side,
                            task.getPeriodStart(), task.getPeriodEnd(), task.getPageCursor());
                    if (page.isEmpty()) {
                        task.finish(RecoveryTaskStatus.SKIPPED, "provider without recovery", now);
                    } else if (page.get().lastPage()) {
                        task.finish(RecoveryTaskStatus.DONE, null, now);
                    } else if (task.getPageCursor() + 1 >= MAX_PAGES) {
                        task.finish(RecoveryTaskStatus.DONE, TRUNCATED, now);
                        log.warn("recovery.truncated firm={} company={} source={} pages={}", task.getAccountingFirmId(),
                                company.getId(), task.getSource(), MAX_PAGES);
                    } else {
                        task.setPageCursor(task.getPageCursor() + 1);
                    }
                }
                case SIEG_NFSE -> {
                    provider.syncServiceInvoices(company.getId(), task.getPeriodStart(), task.getPeriodEnd());
                    task.finish(RecoveryTaskStatus.DONE, null, now);
                }
                default -> task.finish(RecoveryTaskStatus.SKIPPED, "not a SIEG source", now);
            }
        } catch (RuntimeException e) {
            task.failed(e.getClass().getSimpleName());
            if (task.getAttempts() >= MAX_ATTEMPTS) {
                task.finish(RecoveryTaskStatus.SKIPPED, "gave up after " + MAX_ATTEMPTS + " failures: " + e.getClass().getSimpleName(), now);
                log.warn("recovery.step.gave.up firm={} company={} source={} attempts={}", task.getAccountingFirmId(),
                        company.getId(), task.getSource(), MAX_ATTEMPTS);
            } else {
                log.warn("recovery.step.failed firm={} company={} source={} - tried again on the next run: {}",
                        task.getAccountingFirmId(), company.getId(), task.getSource(), e.getClass().getSimpleName());
            }
        }
        taskRepository.save(task);
        if (!task.isPending()) {
            completeIfFinished(task.getAccountingFirmId());
        }
        return true;
    }

    /**
     * Pedaços da SIEG de empresas que a captura não atende mais (inativa, excluída, sem o serviço no plano): SKIPPED,
     * para o escritório concluir. Os de escritório bloqueado ficam parados, não pulados.
     */
    public void skipSiegOrphans(Collection<UUID> servedCompanyIds, RoutineGate gate) {
        skipOrphans(taskRepository.findByStatusAndSourceInAndDeletedIsFalse(RecoveryTaskStatus.PENDING, SIEG_SOURCES),
                task -> servedCompanyIds.contains(task.getCompanyId()), gate, "company not served by the SIEG capture");
    }

    // ------------------------------------------------------------------ e-Fisco (EfiscoSyncScheduler)

    /**
     * Os meses do e-Fisco do escritório, numa chamada só. OK ou PARCIAL (empresa sem IE, extrato com problema - a
     * rotina normal também não insiste) concluem; ERRO, BLOQUEADO e EM_ANDAMENTO tentam no dia seguinte; sem
     * certificado é SKIPPED.
     */
    public void runEfisco(UUID firmId, Function<List<YearMonth>, String> syncFirm) {
        List<RecoveryTask> pending = taskRepository.findByAccountingFirmIdAndStatusAndSourceAndDeletedIsFalse(firmId, RecoveryTaskStatus.PENDING,
                RecoveryTaskSource.EFISCO);
        if (pending.isEmpty()) {
            return;
        }
        List<YearMonth> months = pending.stream().map(task -> YearMonth.from(task.getPeriodStart())).distinct().sorted().toList();
        LocalDateTime now = LocalDateTime.now(clock);
        String status;
        try {
            status = syncFirm.apply(months);
        } catch (RuntimeException e) {
            status = "ERRO:" + e.getClass().getSimpleName();
        }
        for (RecoveryTask task : pending) {
            if ("OK".equals(status) || "PARCIAL".equals(status)) {
                task.finish(RecoveryTaskStatus.DONE, "PARCIAL".equals(status) ? "PARCIAL" : null, now);
            } else if ("SEM_CERTIFICADO".equals(status)) {
                task.finish(RecoveryTaskStatus.SKIPPED, status, now);
            } else {
                task.failed(status);
                if (task.getAttempts() >= MAX_ATTEMPTS) {
                    task.finish(RecoveryTaskStatus.SKIPPED, "gave up after " + MAX_ATTEMPTS + " failures: " + status, now);
                }
            }
        }
        taskRepository.saveAll(pending);
        if (!pending.getFirst().isPending()) {
            completeIfFinished(firmId);
        } else {
            log.warn("recovery.efisco.failed firm={} - tried again on the next run", firmId);
        }
    }

    /** e-Fisco de escritórios que a rotina não atendeu hoje (sem certificado válido): SKIPPED, salvo os bloqueados. */
    public void skipEfiscoOrphans(Collection<UUID> servedFirmIds, RoutineGate gate) {
        skipOrphans(taskRepository.findByStatusAndSourceInAndDeletedIsFalse(RecoveryTaskStatus.PENDING, EnumSet.of(RecoveryTaskSource.EFISCO)),
                task -> servedFirmIds.contains(task.getAccountingFirmId()), gate, "firm without a valid accountant certificate");
    }

    // ------------------------------------------------------------------ common

    private void skipOrphans(List<RecoveryTask> pending, java.util.function.Predicate<RecoveryTask> served, RoutineGate gate,
                             String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        Set<UUID> touched = new java.util.HashSet<>();
        for (RecoveryTask task : pending) {
            if (served.test(task) || !gate.allowsFirm(task.getAccountingFirmId())) {
                continue;
            }
            task.finish(RecoveryTaskStatus.SKIPPED, reason, now);
            taskRepository.save(task);
            touched.add(task.getAccountingFirmId());
        }
        touched.forEach(this::completeIfFinished);
    }

    /** Nada mais PENDING no escritório: limpa o período e grava RECOVERY_DONE com os meses de cada fonte. */
    void completeIfFinished(UUID firmId) {
        transaction.executeWithoutResult(status -> {
            // lock first, then count: a reactivation adding pieces at the same time waits, and nothing is closed too soon
            FirmSubscription subscription = subscriptionRepository.findForUpdate(firmId).orElse(null);
            if (subscription == null || subscription.getRecoveryFrom() == null
                    || taskRepository.countByAccountingFirmIdAndStatusAndDeletedIsFalse(firmId, RecoveryTaskStatus.PENDING) > 0) {
                return;
            }
            LocalDate from = YearMonth.from(subscription.getRecoveryFrom()).atDay(1);
            List<RecoveryTask> tasks = taskRepository.findByAccountingFirmIdAndPeriodStartBetweenAndDeletedIsFalse(firmId, from,
                    subscription.getRecoveryUntil());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("from", subscription.getRecoveryFrom().toString());
            after.put("until", subscription.getRecoveryUntil().toString());
            for (RecoveryTaskSource source : RecoveryTaskSource.values()) {
                List<String> months = tasks.stream().filter(task -> task.getSource() == source && task.getStatus() == RecoveryTaskStatus.DONE)
                        .map(task -> YearMonth.from(task.getPeriodStart()).toString()).distinct().sorted().toList();
                if (!months.isEmpty()) {
                    after.put(source.name(), months);
                }
            }
            after.put("skipped", tasks.stream().filter(task -> task.getStatus() == RecoveryTaskStatus.SKIPPED).count());
            List<String> truncated = tasks.stream().filter(task -> TRUNCATED.equals(task.getLastError()))
                    .map(task -> task.getSource() + "@" + YearMonth.from(task.getPeriodStart())).sorted().toList();
            if (!truncated.isEmpty()) {
                after.put("truncated", truncated);
            }
            subscription.clearRecovery();
            subscriptionRepository.save(subscription);
            eventService.recordFields(firmId, FirmEventType.RECOVERY_DONE, null, after, null, FirmEventService.SYSTEM);
            log.info("recovery.done firm={} tasks={}", firmId, tasks.size());
        });
    }

    /** Os meses ainda em recuperação do escritório ("2026-12"), para o aviso da tela. */
    public List<String> pendingMonths(UUID firmId) {
        return taskRepository.findByAccountingFirmIdAndStatusAndDeletedIsFalse(firmId, RecoveryTaskStatus.PENDING).stream()
                .map(task -> YearMonth.from(task.getPeriodStart()).toString()).distinct().sorted().toList();
    }

    private static Map<String, Object> summary(RecoveryPlan.Plan plan) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sieg_nfe", plan.nfeMonths().stream().map(YearMonth::toString).toList());
        List<String> nfse = new ArrayList<>();
        plan.nfseWindows().forEach(window -> nfse.add(window.start() + ".." + window.end()));
        fields.put("sieg_nfse", nfse);
        fields.put("efisco", plan.efiscoMonths().stream().map(YearMonth::toString).toList());
        return fields;
    }

    private static LocalDate firstDay(RecoveryPlan.Plan plan) {
        LocalDate first = null;
        for (YearMonth month : plan.nfeMonths()) {
            first = min(first, month.atDay(1));
        }
        for (YearMonth month : plan.efiscoMonths()) {
            first = min(first, month.atDay(1));
        }
        for (RecoveryPlan.Window window : plan.nfseWindows()) {
            first = min(first, window.start());
        }
        return first;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a == null || b.isBefore(a) ? b : a;
    }
}
