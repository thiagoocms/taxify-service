package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.domain.capture.CapturePriorityRequest;
import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureStage;
import br.com.cshub.taxify.service.capture.CaptureFailureClassifier;
import br.com.cshub.taxify.service.capture.CapturePriorityService;
import br.com.cshub.taxify.service.capture.CaptureStageRecorder;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.BlockedPeriodRecoveryService;
import org.springframework.dao.DataIntegrityViolationException;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Periodically pulls new fiscal XMLs for companies, so the search endpoint can serve
 * everything from the database instead of calling a provider on every request.
 * Provider-agnostic: which {@link FiscalXmlProvider} runs is picked at runtime from
 * {@link FiscalXmlSyncProperties#getProvider()} (default "sieg") - this class never
 * references SIEG (or any other provider) directly.
 *
 * <p>Batches over {@link FiscalXmlSyncControl} rather than {@link Company} directly:
 * that row already carries {@link FiscalXmlSyncControl#getTotalImportado()}, which feeds
 * {@link FiscalXmlSyncRequest#alreadyImportedCount()} so a paginating provider (SIEG's
 * {@code skip}) resumes after what's already persisted instead of re-requesting the same
 * page every tick. {@link #ensureControlRowsExist} keeps that table complete by
 * creating a zeroed row for any company that doesn't have one yet, so a brand new
 * company is never permanently left out of the rotation.
 *
 * <p>{@link #syncCompany} always asks for the whole current month (day 1 through today),
 * not a rolling lookback window - so "quantas notas tem" and "quantas já importei" stay
 * comparable to the same period every tick. When the control row already shows pending
 * notes ({@code totalImportado < totalSieg}), {@link FiscalXmlSyncRequest#knownPending()}
 * tells the provider to skip its own count check and go straight to downloading - saves a
 * rate-limited request for something already established from an earlier tick.
 *
 * <p>Only companies entitled to {@link PlanServiceEnum#FISCAL_XML_IMPORT} with SIEG as a source
 * ({@link PlanServiceEnum#SIEG_INTEGRATION}) are actually
 * synced: {@link #hasFiscalXmlImportEntitlement} requires a currently "vigente" (active -
 * {@code startDate <= today <= endDate}) {@link Subscription} whose {@link
 * br.com.cshub.taxify.domain.plan.Plan} includes that service. {@link
 * #ensureControlRowsExist} still seeds a control row for every company of a firm not blocked (F1-RN-10) -
 * the control table stays a complete picture of every company, it's only the actual
 * provider call that's gated by the plan.
 *
 * <p>A provider's rate limit is account-wide, not per company (SIEG: 2 requests/minute
 * total), so once there are more companies than that limit allows per tick, trying to
 * sync everyone in a single run would block this thread for minutes at a time. Instead,
 * each tick only syncs {@link FiscalXmlSyncProperties#getBatchSize()} companies - {@code cron} is meant to fire
 * often (each tick does bounded, fast work) rather than rarely-but-long-running.
 *
 * <p>Which companies: a queue persisted on the control rows ({@link #queueOrder}) - the oldest last attempt first,
 * never attempted before everything else; among those, the oldest real verification first (never verified first); then
 * the id, so the order is stable. Every attempt - success, failure or skipped as up to date - moves the company to the
 * end of the queue ({@code last_attempt_at}), so a company that always fails never holds the others back, and a
 * restart or deploy does not send the queue back to the same few companies (the old in-memory cursor did, over a list
 * read without ORDER BY).
 */
@Component
public class FiscalXmlSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlSyncScheduler.class);
    /** Today plus the 2 previous days: covers the 24h cancellation window with margin for late uploads. */
    static final int EVENT_LOOKBACK_DAYS = 2;
    /** NFS-e can be issued days after the service and some cities send them late - a month and a bit. */
    static final int SERVICE_INVOICE_LOOKBACK_DAYS = 35;
    static final int SERVICE_INVOICE_FIRST_MONTHS = 3;

    private final CompanyRepository companyRepository;
    private final FiscalXmlSyncControlRepository syncControlRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final Map<String, FiscalXmlProvider> providers;
    private final FiscalXmlSyncProperties properties;
    private final FirmAccessService firmAccessService;
    /** Recovery of the blocked months (QA-02); null = no recovery (tests built without it). */
    private final BlockedPeriodRecoveryService recoveryService;
    /** America/Recife (ClockConfig) - the queue's last_attempt_at. */
    private final Clock clock;
    /** The capture panel's result of each stage (CP-RN-10); never throws, never changes the capture's flow. */
    private final CaptureStageRecorder stageRecorder;
    /** "Sincronizar agora" (CP-RN-15); null = no priority (tests built without it). */
    private final CapturePriorityService priorityService;

    public FiscalXmlSyncScheduler(CompanyRepository companyRepository, FiscalXmlSyncControlRepository syncControlRepository,
                                   SubscriptionRepository subscriptionRepository, Map<String, FiscalXmlProvider> providers,
                                   FiscalXmlSyncProperties properties, FirmAccessService firmAccessService) {
        this(companyRepository, syncControlRepository, subscriptionRepository, providers, properties, firmAccessService, null,
                Clock.system(ZoneId.of("America/Recife")));
    }

    public FiscalXmlSyncScheduler(CompanyRepository companyRepository, FiscalXmlSyncControlRepository syncControlRepository,
                                   SubscriptionRepository subscriptionRepository, Map<String, FiscalXmlProvider> providers,
                                   FiscalXmlSyncProperties properties, FirmAccessService firmAccessService,
                                   BlockedPeriodRecoveryService recoveryService, Clock clock) {
        this(companyRepository, syncControlRepository, subscriptionRepository, providers, properties, firmAccessService,
                recoveryService, clock, null, null);
    }

    @Autowired
    public FiscalXmlSyncScheduler(CompanyRepository companyRepository, FiscalXmlSyncControlRepository syncControlRepository,
                                   SubscriptionRepository subscriptionRepository, Map<String, FiscalXmlProvider> providers,
                                   FiscalXmlSyncProperties properties, FirmAccessService firmAccessService,
                                   BlockedPeriodRecoveryService recoveryService, Clock clock,
                                   CaptureStageRecorder stageRecorder, CapturePriorityService priorityService) {
        this.stageRecorder = stageRecorder == null ? CaptureStageRecorder.noop() : stageRecorder;
        this.priorityService = priorityService;
        this.clock = clock;
        this.firmAccessService = firmAccessService;
        this.recoveryService = recoveryService;
        this.companyRepository = companyRepository;
        this.syncControlRepository = syncControlRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.providers = providers;
        this.properties = properties;
    }

    @Scheduled(cron = "${fiscal-xml.sync.cron:0 * * * * *}")
    public void syncAllCompanies() {
        if (!properties.isEnabled()) {
            return;
        }

        FiscalXmlProvider provider = providers.get(properties.getProvider());
        if (provider == null) {
            log.warn("No FiscalXmlProvider registered for key '{}' (fiscal-xml.sync.provider) - skipping sync. Known providers: {}",
                    properties.getProvider(), providers.keySet());
            return;
        }

        // F1-RN-10: one gate for the whole tick - a blocked firm neither gets new control rows nor is synced
        RoutineGate gate = firmAccessService.routineGate("sieg");
        ensureControlRowsExist(gate);

        LocalDate today = properties.getReferenceDate() != null ? properties.getReferenceDate() : LocalDate.now(clock);

        // F1-RN-10: no capture for a blocked, suspended, canceled or unverified firm
        List<FiscalXmlSyncControl> controls = syncControlRepository.findAllWithCompany().stream()
                .filter(control -> gate.allows(control.getCompany()))
                .filter(control -> hasFiscalXmlImportEntitlement(control.getCompany(), today))
                .toList();
        gate.logSkipped();
        if (recoveryService != null) {
            // recovery pieces of companies this capture no longer serves are skipped, so the firm can finish
            recoveryService.skipSiegOrphans(controls.stream().map(control -> control.getCompany().getId()).toList(), gate);
        }
        // CP-RN-18: before anything else, the open "Sincronizar agora" requests that expired or that this capture no
        // longer serves (blocked firm, lost entitlement) are closed - even when nobody is left to sync
        List<CapturePriorityRequest> open = openPriorityRequests(controls);
        if (controls.isEmpty()) {
            return;
        }

        List<FiscalXmlSyncControl> queue = controls.stream().sorted(queueOrder()).toList();
        LocalDateTime head = queue.getFirst().getLastAttemptAt();
        List<Slot> batch = nextBatch(queue, properties.getBatchSize(), open);
        log.info("Starting scheduled fiscal XML sync (provider={}): {} of {} entitled companies this tick ({} priority), first in queue last attempted at {}",
                properties.getProvider(), batch.size(), controls.size(), batch.stream().filter(Slot::forced).count(),
                head == null ? "never" : head);

        for (Slot slot : batch) {
            FiscalXmlSyncControl control = slot.control();
            try {
                syncCompany(provider, control, today, slot.forced());
            } catch (Exception e) {
                log.warn("Scheduled fiscal XML sync failed for company {}", control.getCompany().getId(), e);
            } finally {
                if (slot.request() != null) {
                    priorityService.served(slot.request());
                }
            }
        }
    }

    /**
     * The open "Sincronizar agora" requests for this tick. A failure here (the requests table unavailable) never takes the
     * SIEG round down: the tick goes on as a regular one, without priority, and the requests wait for the next tick.
     */
    private List<CapturePriorityRequest> openPriorityRequests(List<FiscalXmlSyncControl> controls) {
        if (priorityService == null) {
            return List.of();
        }
        try {
            return priorityService.prepareTick(controls.stream().map(control -> control.getCompany().getId())
                    .collect(Collectors.toSet()));
        } catch (RuntimeException e) {
            log.warn("capture.priority.tick.failed - this tick runs without priority: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * One company of the tick. {@code request}: the open "Sincronizar agora" it serves (null = none); {@code forced}: the
     * attempt counts both sides again on the provider even when a side looks up to date (CP-RN-15).
     */
    record Slot(FiscalXmlSyncControl control, CapturePriorityRequest request, boolean forced) {
    }

    /**
     * "Vigente" - {@code startDate <= today <= endDate} - and the plan attached to that
     * subscription lists {@link PlanServiceEnum#FISCAL_XML_IMPORT} and, as the source,
     * {@link PlanServiceEnum#SIEG_INTEGRATION} among its services (SEFAZ is SefazDistScheduler's). No
     * active subscription, or an active one on a plan without this service, both mean
     * "not entitled" - the company is simply left out of the tick (its control row is
     * untouched, not deleted or flagged).
     */
    private boolean hasFiscalXmlImportEntitlement(Company company, LocalDate today) {
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        company.getId(), today, today)
                .map(Subscription::getPlan)
                .map(plan -> PlanServiceEnum.importsFrom(plan.getServices(), PlanServiceEnum.SIEG_INTEGRATION))
                .orElse(false);
    }

    /**
     * A company with no {@link FiscalXmlSyncControl} row yet (never synced) would
     * otherwise never appear in {@link FiscalXmlSyncControlRepository#findAllWithCompany()}
     * and be starved forever - this seeds a zeroed row for it so the next batch picks it
     * up like any other company.
     */
    private void ensureControlRowsExist(RoutineGate gate) {
        List<Company> companies = companyRepository.findAll();
        if (companies.isEmpty()) {
            return;
        }

        Set<UUID> withControl = new HashSet<>(syncControlRepository.findAllCompanyIds());
        for (Company company : companies) {
            // a blocked firm's company gets its row on the first tick after the firm is reactivated
            boolean blocked = company.getAccountingFirm() != null && !gate.allowsFirm(company.getAccountingFirm().getId());
            if (!withControl.contains(company.getId()) && !blocked) {
                FiscalXmlSyncControl control = new FiscalXmlSyncControl();
                control.setCompany(company);
                control.setDeleted(false);
                control.setTotalSieg(0);
                control.setTotalImportado(0);
                // filled because the column is NOT NULL - not a verification (see FiscalXmlSyncControl#getVerifiedAt)
                control.setUltimaVerificacaoEm(LocalDateTime.now(clock));
                seed(control);
            }
        }
    }

    /**
     * One row at a time: two instances seeding together - the loser's INSERT hits uk_fiscal_xml_sync_control_company,
     * which only means the other one created it. Nothing here takes the tick down.
     */
    private void seed(FiscalXmlSyncControl control) {
        UUID companyId = control.getCompany().getId();
        try {
            syncControlRepository.save(control);
        } catch (DataIntegrityViolationException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                log.info("Sync control row of company {} already created by another instance", companyId);
            } else {
                log.warn("Sync control row of company {} not created - tried again on the next tick: {}", companyId,
                        e.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            log.warn("Sync control row of company {} not created - tried again on the next tick: {}", companyId,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * The first {@code batchSize} companies of the queue that this instance manages to reserve ({@link #reserve}): one
     * taken meanwhile by another instance is skipped and the next one in the queue is tried, until the batch is full.
     */
    private List<Slot> nextBatch(List<FiscalXmlSyncControl> queue, int batchSize, List<CapturePriorityRequest> open) {
        int wanted = Math.min(Math.max(batchSize, 1), queue.size());
        List<Slot> batch = new ArrayList<>(wanted);
        Set<UUID> taken = new HashSet<>();
        Map<UUID, CapturePriorityRequest> openByCompany = new HashMap<>();
        open.forEach(request -> openByCompany.putIfAbsent(request.getCompanyId(), request));

        // CP-RN-15: the first slots go to the open requests, oldest first (at most batch-size - 1; with 1, every other tick)
        int prioritySlots = priorityService == null ? 0 : Math.min(wanted, priorityService.prioritySlots(batchSize, open.size()));
        if (prioritySlots > 0) {
            Map<UUID, FiscalXmlSyncControl> byCompany = new HashMap<>();
            queue.forEach(control -> byCompany.put(control.getCompany().getId(), control));
            for (CapturePriorityRequest request : open) {
                if (batch.size() >= prioritySlots) {
                    break;
                }
                FiscalXmlSyncControl control = byCompany.get(request.getCompanyId());
                if (control == null || taken.contains(request.getCompanyId())) {
                    continue;
                }
                if (reserve(control)) {
                    priorityService.reserved(request);
                    taken.add(request.getCompanyId());
                    batch.add(new Slot(control, request, true));
                }
            }
        }
        for (FiscalXmlSyncControl control : queue) {
            if (batch.size() >= wanted) {
                break;
            }
            UUID companyId = control.getCompany().getId();
            if (taken.contains(companyId)) {
                continue;
            }
            if (reserve(control)) {
                taken.add(companyId);
                // CP-RN-18: the regular queue reached a company with an open request - that attempt serves it
                CapturePriorityRequest request = openByCompany.get(companyId);
                if (request != null) {
                    priorityService.reserved(request);
                }
                batch.add(new Slot(control, request, request != null));
            }
        }
        return batch;
    }

    /**
     * The queue: last attempt (never first), then the real verification (never first - see
     * {@link FiscalXmlSyncControl#getVerifiedAt()}), then the row id and the company id - stable between ticks.
     */
    static Comparator<FiscalXmlSyncControl> queueOrder() {
        return Comparator.comparing(FiscalXmlSyncControl::getLastAttemptAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(FiscalXmlSyncControl::getVerifiedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(control -> control.getId() == null ? "" : control.getId().toString())
                .thenComparing(control -> control.getCompany() == null ? "" : control.getCompany().getId().toString());
    }

    /**
     * Before the sync, atomically: {@code last_attempt_at} moves to now (Recife, microseconds as in datetime(6)) only if
     * it is still what was read. Whatever happens next (success, failure, skipped as up to date), the company is at the
     * end of the queue. Not reserved (another instance took it, or the update failed) = not synced by this tick.
     */
    private boolean reserve(FiscalXmlSyncControl control) {
        LocalDateTime read = control.getLastAttemptAt();
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (control.getId() == null) {
            // not persisted (never the case for rows read from the database): nothing to reserve against
            control.setLastAttemptAt(now);
            return true;
        }
        try {
            if (syncControlRepository.reserve(control.getId(), read, now) == 1) {
                control.setLastAttemptAt(now);
                return true;
            }
            log.info("Company {} already taken by another instance this tick - skipped", control.getCompany().getId());
            return false;
        } catch (Exception e) {
            log.warn("Could not reserve company {} for this tick - skipped", control.getCompany().getId(), e);
            return false;
        }
    }

    /**
     * Received notes first (what most fiscal work starts from), then the company's own; NFS-e once a day. A side that
     * fails still aborts the rest of the company for this attempt (unchanged): the stages that did not run record nothing.
     *
     * <p>Each stage's provider calls carry its purpose (the provider's request counter, A5); a "Sincronizar agora" counts
     * all of them as priority.
     *
     * @param forced a "Sincronizar agora": both sides are counted on the provider again, even the ones up to date today
     */
    private void syncCompany(FiscalXmlProvider provider, FiscalXmlSyncControl control, LocalDate today, boolean forced) {
        ProviderCallContext.priority(forced, () -> {
            Map<FiscalXmlSide, Integer> serviceInvoiceCounts = new EnumMap<>(FiscalXmlSide.class);
            for (FiscalXmlSide side : FiscalXmlSide.values()) {
                Integer count = ProviderCallContext.call(ProviderCallPurpose.PAGE,
                        () -> syncSide(provider, control, side, today, forced));
                if (count != null) {
                    serviceInvoiceCounts.put(side, count);
                }
                ProviderCallContext.run(ProviderCallPurpose.EVENTS, () -> refreshEventsOncePerDay(provider, control, side, today));
            }
            // the issued side's NF-e count filters by CnpjEmit, the received side's by CnpjDest
            ProviderCallContext.run(ProviderCallPurpose.NFSE, () -> syncServiceInvoicesOncePerDay(provider, control, today,
                    serviceInvoiceCounts.get(FiscalXmlSide.ISSUED), serviceInvoiceCounts.get(FiscalXmlSide.RECEIVED)));
            ProviderCallContext.run(ProviderCallPurpose.RECOVERY, () -> recoverBlockedMonths(provider, control));
        });
    }

    /**
     * QA-02: after the firm's reactivation, one step of the months that stayed out of the regular window (one SIEG page,
     * or one NFS-e window) on each of the company's turns - the rotation keeps SIEG's rate limit. A failure stays
     * pending for the next turn. Recorded as SIEG_RECOVERY: the step's result; a piece given up after too many failures
     * is a warning (SIEG_RECOVERY_GAVE_UP), not a permanent error; with nothing left to recover, an open failure of the
     * stage is closed the same way (it would never be retried).
     */
    private void recoverBlockedMonths(FiscalXmlProvider provider, FiscalXmlSyncControl control) {
        if (recoveryService == null) {
            return;
        }
        UUID companyId = control.getCompany().getId();
        try {
            BlockedPeriodRecoveryService.SiegStep step = recoveryService.runNextSiegStepReporting(provider, control.getCompany());
            if (step == null || !step.ran()) {
                stageRecorder.closeFailure(companyId, CaptureStage.SIEG_RECOVERY, CaptureFailureCode.SIEG_RECOVERY_GAVE_UP);
            } else if (step.failure() == null) {
                stageRecorder.success(companyId, CaptureStage.SIEG_RECOVERY);
            } else if (step.gaveUp()) {
                stageRecorder.success(companyId, CaptureStage.SIEG_RECOVERY, CaptureFailureCode.SIEG_RECOVERY_GAVE_UP, null);
            } else {
                recordSiegFailure(control, CaptureStage.SIEG_RECOVERY, step.failure());
            }
        } catch (Exception e) {
            log.warn("Blocked-months recovery failed for company {}", companyId, e);
            recordSiegFailure(control, CaptureStage.SIEG_RECOVERY, e);
        }
    }

    /**
     * CP-RN-11: the safe code of a SIEG stage's failure, chosen by the error's type - a company without a CNPJ by the
     * company itself (the capture breaks on it before any call). Only ids and the code reach the log.
     */
    private void recordSiegFailure(FiscalXmlSyncControl control, CaptureStage stage, Throwable error) {
        Company company = control.getCompany();
        boolean withoutCnpj = company.getDocument() == null || company.getDocument().getNumber() == null
                || company.getDocument().getNumber().isBlank();
        stageRecorder.failure(company.getId(), stage, withoutCnpj
                ? new CaptureFailureClassifier.Classified(CaptureFailureCode.COMPANY_WITHOUT_CNPJ, null)
                : CaptureFailureClassifier.sieg(error));
    }

    void syncServiceInvoicesOncePerDay(FiscalXmlProvider provider, FiscalXmlSyncControl control, LocalDate today) {
        syncServiceInvoicesOncePerDay(provider, control, today, null, null);
    }

    /**
     * Service invoices (NFS-e), provided and taken: once a day per company. The first time, the last
     * {@link #SERVICE_INVOICE_FIRST_MONTHS} months are downloaded; afterwards the last {@link #SERVICE_INVOICE_LOOKBACK_DAYS}
     * days (NFS-e from city halls arrive late). How (A1, {@link FiscalXmlSyncProperties#getServiceInvoiceCountGate()}):
     * <ul>
     *   <li>OFF: the whole lookback is downloaded every day;</li>
     *   <li>SHADOW (default): same download, and the current month's provider count this attempt's NF-e sides already got
     *   ({@code issuerCount}/{@code recipientCount}, free) is compared with what the provider had delivered - logged as
     *   what the gate would have done, nothing skipped;</li>
     *   <li>ON: the lookback in windows of one calendar month; each is downloaded only when the provider counts more than it
     *   already delivered - the current month with this attempt's counts, the older windows with a count of their own.</li>
     * </ul>
     */
    void syncServiceInvoicesOncePerDay(FiscalXmlProvider provider, FiscalXmlSyncControl control, LocalDate today,
                                       Integer issuerCount, Integer recipientCount) {
        if (today.equals(control.getServiceInvoicesCheckedOn())) {
            return;
        }
        UUID companyId = control.getCompany().getId();
        try {
            int created = syncServiceInvoices(provider, control, today, issuerCount, recipientCount);
            syncControlRepository.markServiceInvoicesChecked(control.getId(), today);
            if (created > 0) {
                log.info("Service invoices for company {}: {} new", companyId, created);
            }
            stageRecorder.success(companyId, CaptureStage.SIEG_NFSE);
        } catch (Exception e) {
            // not marked - the next tick tries again
            log.warn("Service invoice sync failed for company {}", companyId, e);
            recordSiegFailure(control, CaptureStage.SIEG_NFSE, e);
        }
    }

    private int syncServiceInvoices(FiscalXmlProvider provider, FiscalXmlSyncControl control, LocalDate today,
                                    Integer issuerCount, Integer recipientCount) {
        UUID companyId = control.getCompany().getId();
        if (control.getServiceInvoicesCheckedOn() == null) {
            return provider.syncServiceInvoices(companyId, today.minusMonths(SERVICE_INVOICE_FIRST_MONTHS).withDayOfMonth(1), today);
        }
        LocalDate from = today.minusDays(SERVICE_INVOICE_LOOKBACK_DAYS);
        return switch (properties.getServiceInvoiceCountGate()) {
            case OFF -> provider.syncServiceInvoices(companyId, from, today);
            case SHADOW -> provider.syncServiceInvoicesShadow(companyId, from, today, issuerCount, recipientCount);
            case ON -> {
                int created = 0;
                for (LocalDate[] window : monthWindows(from, today)) {
                    boolean current = !window[0].isBefore(today.withDayOfMonth(1));
                    created += provider.syncServiceInvoicesIfMissing(companyId, window[0], window[1],
                            current ? issuerCount : null, current ? recipientCount : null);
                }
                yield created;
            }
        };
    }

    /** [from, to] in windows that never cross a calendar month: the first starts at {@code from}, the last ends at {@code to}. */
    static List<LocalDate[]> monthWindows(LocalDate from, LocalDate to) {
        List<LocalDate[]> windows = new ArrayList<>();
        for (LocalDate start = from; !start.isAfter(to); ) {
            LocalDate monthEnd = start.withDayOfMonth(start.lengthOfMonth());
            LocalDate end = monthEnd.isAfter(to) ? to : monthEnd;
            windows.add(new LocalDate[]{start, end});
            start = end.plusDays(1);
        }
        return windows;
    }

    /**
     * A note can be canceled up to 24h after authorization - usually after the regular sync
     * already imported it. Once a day per side, the last {@link #EVENT_LOOKBACK_DAYS} days of
     * notes are re-read with their events so those cancellations are caught too.
     */
    private void refreshEventsOncePerDay(FiscalXmlProvider provider, FiscalXmlSyncControl control, FiscalXmlSide side, LocalDate today) {
        LocalDate lastChecked = side == FiscalXmlSide.ISSUED ? control.getIssuedEventsCheckedOn() : control.getReceivedEventsCheckedOn();
        if (today.equals(lastChecked)) {
            return;
        }
        UUID companyId = control.getCompany().getId();
        try {
            int events = provider.refreshEvents(companyId, side, today.minusDays(EVENT_LOOKBACK_DAYS), today);
            if (side == FiscalXmlSide.ISSUED) {
                syncControlRepository.markIssuedEventsChecked(control.getId(), today);
            } else {
                syncControlRepository.markReceivedEventsChecked(control.getId(), today);
            }
            if (events > 0) {
                log.info("Event sweep for company {} ({}): {} new events", companyId, side, events);
            }
            stageRecorder.success(companyId, CaptureStage.siegEvents(side == FiscalXmlSide.ISSUED));
        } catch (Exception e) {
            // not marked - the next tick tries again
            log.warn("Event sweep failed for company {} ({})", companyId, side, e);
            recordSiegFailure(control, CaptureStage.siegEvents(side == FiscalXmlSide.ISSUED), e);
        }
    }

    /** @return the provider's NFS-e count for the side's window when this sync counted; null otherwise */
    private Integer syncSide(FiscalXmlProvider provider, FiscalXmlSyncControl control, FiscalXmlSide side, LocalDate today,
                             boolean forced) {
        Company company = control.getCompany();
        SideState state = SideState.of(control, side, YearMonth.from(today).toString());
        CaptureStage stage = CaptureStage.siegSide(side == FiscalXmlSide.ISSUED);

        if (state.isUpToDate(today, LocalDateTime.now(clock), properties.getZeroTotalRecountHours()) && !forced) {
            // skipped without a call: nothing recorded (CP-RN-10)
            log.info("Company {} {} side already fully synced for {} ({} of {} imported) - skipping, no request spent",
                    company.getId(), side, today, state.imported(), state.totalSieg());
            return null;
        }

        // The totals refer to whichever window was last checked - once the calendar month rolls
        // over that's last month's, so reusing them as this month's skip (or to claim "known
        // pending") would be wrong. A never-checked side is treated the same: nothing to carry.
        boolean sameMonthAsLastCheck = state.checkedAt() != null
                && YearMonth.from(state.checkedAt()).equals(YearMonth.from(today));
        int alreadyImportedCount = sameMonthAsLastCheck ? state.imported() : 0;
        // a forced attempt counts again: the user knows a note arrived after the last check (CP-RN-15)
        // B1: the difference a complete pass of this month already left (notes SIEG counts and never delivers) is not
        // "pending" - only what goes beyond it
        boolean knownPending = !forced && sameMonthAsLastCheck && state.totalSieg() > 0
                && state.imported() + state.knownGap() < state.totalSieg();

        FiscalXmlSyncResult result;
        try {
            result = provider.sync(new FiscalXmlSyncRequest(
                    company.getId(), today.withDayOfMonth(1), today, alreadyImportedCount, knownPending, side));
        } catch (RuntimeException e) {
            recordSiegFailure(control, stage, e);
            throw e;
        }
        log.info("Fiscal XML sync for company {} ({}): {} new, {} duplicated, {} parse errors",
                company.getId(), side, result.persistedNew(), result.duplicated(), result.parseErrors());
        if (result.countFailed()) {
            stageRecorder.success(company.getId(), stage, CaptureFailureCode.SIEG_COUNT_FAILED, null);
        } else if (result.parseErrors() > 0) {
            stageRecorder.success(company.getId(), stage, CaptureFailureCode.XML_PARSE_ERRORS, result.parseErrors());
        } else {
            stageRecorder.success(company.getId(), stage);
        }
        return result.serviceInvoiceCount();
    }

    /**
     * One side's bookkeeping, read from its own columns on the control row (null = never checked). {@code verified}: the
     * side was really checked once - the received side's time is filled on the row's creation, so it only counts once the
     * window was written ({@code data_emissao_inicio}). {@code knownGap}: the difference the month's last complete pass
     * left (B1) - the side is up to date when imported + knownGap reaches SIEG's total.
     */
    record SideState(int totalSieg, int imported, LocalDateTime checkedAt, boolean verified, int knownGap) {

        static SideState of(FiscalXmlSyncControl control, FiscalXmlSide side, String month) {
            boolean issued = side == FiscalXmlSide.ISSUED;
            int gap = control.knownGap(issued, month);
            if (issued) {
                return new SideState(valueOf(control.getIssuedTotalSieg()), valueOf(control.getIssuedTotalImported()),
                        control.getIssuedCheckedAt(), control.getIssuedCheckedAt() != null, gap);
            }
            return new SideState(control.getTotalSieg(), control.getTotalImportado(), control.getUltimaVerificacaoEm(),
                    control.getUltimaVerificacaoEm() != null && control.getDataEmissaoInicio() != null, gap);
        }

        /**
         * Everything the provider reported is already imported, checked today - no request needed. A3: a side really
         * checked today whose total is 0 is up to date too, but only for {@code zeroRecountHours} after that check
         * (0 = never); then it is counted again.
         */
        boolean isUpToDate(LocalDate today, LocalDateTime now, int zeroRecountHours) {
            if (checkedAt == null || !checkedAt.toLocalDate().isEqual(today) || imported + knownGap < totalSieg) {
                return false;
            }
            if (totalSieg > 0) {
                return true;
            }
            return verified && zeroRecountHours > 0 && !checkedAt.isBefore(now.minusHours(zeroRecountHours));
        }

        private static int valueOf(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
