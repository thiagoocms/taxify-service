package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * #ensureControlRowsExist} still seeds a control row for every company regardless -
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
 * end of the queue ({@code last_attempt_at}, reserved before the sync), so a company that always fails never holds the
 * others back, and a restart or deploy does not send the queue back to the same few companies (the old in-memory
 * cursor did, over a list read without ORDER BY).
 */
@Component
public class FiscalXmlSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlSyncScheduler.class);
    /** Today plus the 2 previous days: covers the 24h cancellation window with margin for late uploads. */
    static final int EVENT_LOOKBACK_DAYS = 2;
    /** NFS-e can be issued days after the service and some cities send them late - a month and a bit. */
    static final int SERVICE_INVOICE_LOOKBACK_DAYS = 35;
    static final int SERVICE_INVOICE_FIRST_MONTHS = 3;
    static final ZoneId RECIFE = ZoneId.of("America/Recife");

    private final CompanyRepository companyRepository;
    private final FiscalXmlSyncControlRepository syncControlRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final Map<String, FiscalXmlProvider> providers;
    private final FiscalXmlSyncProperties properties;
    /** America/Recife - the queue's last_attempt_at and the creation time of the control rows. */
    private final Clock clock;

    @Autowired
    public FiscalXmlSyncScheduler(CompanyRepository companyRepository, FiscalXmlSyncControlRepository syncControlRepository,
                                   SubscriptionRepository subscriptionRepository, Map<String, FiscalXmlProvider> providers,
                                   FiscalXmlSyncProperties properties) {
        this(companyRepository, syncControlRepository, subscriptionRepository, providers, properties, Clock.system(RECIFE));
    }

    /** With a given clock (tests); production uses Recife's system clock. */
    public FiscalXmlSyncScheduler(CompanyRepository companyRepository, FiscalXmlSyncControlRepository syncControlRepository,
                                   SubscriptionRepository subscriptionRepository, Map<String, FiscalXmlProvider> providers,
                                   FiscalXmlSyncProperties properties, Clock clock) {
        this.clock = clock;
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

        ensureControlRowsExist();

        LocalDate today = properties.getReferenceDate() != null ? properties.getReferenceDate() : LocalDate.now();

        List<FiscalXmlSyncControl> controls = syncControlRepository.findAllWithCompany().stream()
                .filter(control -> hasFiscalXmlImportEntitlement(control.getCompany(), today))
                .toList();
        if (controls.isEmpty()) {
            return;
        }

        List<FiscalXmlSyncControl> queue = controls.stream().sorted(queueOrder()).toList();
        LocalDateTime head = queue.getFirst().getLastAttemptAt();
        List<FiscalXmlSyncControl> batch = nextBatch(queue, properties.getBatchSize());
        log.info("Starting scheduled fiscal XML sync (provider={}): {} of {} entitled companies this tick, first in queue last attempted at {}",
                properties.getProvider(), batch.size(), controls.size(), head == null ? "never" : head);

        for (FiscalXmlSyncControl control : batch) {
            try {
                syncCompany(provider, control, today);
            } catch (Exception e) {
                log.warn("Scheduled fiscal XML sync failed for company {}", control.getCompany().getId(), e);
            }
        }
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
    private void ensureControlRowsExist() {
        List<Company> companies = companyRepository.findAll();
        if (companies.isEmpty()) {
            return;
        }

        Set<UUID> withControl = new HashSet<>(syncControlRepository.findAllCompanyIds());
        for (Company company : companies) {
            if (!withControl.contains(company.getId())) {
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
    private List<FiscalXmlSyncControl> nextBatch(List<FiscalXmlSyncControl> queue, int batchSize) {
        int wanted = Math.min(Math.max(batchSize, 1), queue.size());
        List<FiscalXmlSyncControl> batch = new ArrayList<>(wanted);
        for (FiscalXmlSyncControl control : queue) {
            if (batch.size() >= wanted) {
                break;
            }
            if (reserve(control)) {
                batch.add(control);
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

    /** Received notes first (what most fiscal work starts from), then the company's own; NFS-e once a day. */
    private void syncCompany(FiscalXmlProvider provider, FiscalXmlSyncControl control, LocalDate today) {
        for (FiscalXmlSide side : FiscalXmlSide.values()) {
            syncSide(provider, control, side, today);
            refreshEventsOncePerDay(provider, control, side, today);
        }
        syncServiceInvoicesOncePerDay(provider, control, today);
    }

    /**
     * Service invoices (NFS-e), provided and taken: once a day per company, the last
     * {@link #SERVICE_INVOICE_LOOKBACK_DAYS} days; the first time, the last
     * {@link #SERVICE_INVOICE_FIRST_MONTHS} months. Known notes are skipped, so the overlap only costs requests.
     */
    void syncServiceInvoicesOncePerDay(FiscalXmlProvider provider, FiscalXmlSyncControl control, LocalDate today) {
        if (today.equals(control.getServiceInvoicesCheckedOn())) {
            return;
        }
        UUID companyId = control.getCompany().getId();
        LocalDate from = control.getServiceInvoicesCheckedOn() == null
                ? today.minusMonths(SERVICE_INVOICE_FIRST_MONTHS).withDayOfMonth(1)
                : today.minusDays(SERVICE_INVOICE_LOOKBACK_DAYS);
        try {
            int created = provider.syncServiceInvoices(companyId, from, today);
            syncControlRepository.markServiceInvoicesChecked(control.getId(), today);
            if (created > 0) {
                log.info("Service invoices for company {}: {} new", companyId, created);
            }
        } catch (Exception e) {
            // not marked - the next tick tries again
            log.warn("Service invoice sync failed for company {}", companyId, e);
        }
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
        } catch (Exception e) {
            // not marked - the next tick tries again
            log.warn("Event sweep failed for company {} ({})", companyId, side, e);
        }
    }

    private void syncSide(FiscalXmlProvider provider, FiscalXmlSyncControl control, FiscalXmlSide side, LocalDate today) {
        Company company = control.getCompany();
        SideState state = SideState.of(control, side);

        if (state.isUpToDate(today)) {
            log.info("Company {} {} side already fully synced for {} ({} of {} imported) - skipping, no request spent",
                    company.getId(), side, today, state.imported(), state.totalSieg());
            return;
        }

        // The totals refer to whichever window was last checked - once the calendar month rolls
        // over that's last month's, so reusing them as this month's skip (or to claim "known
        // pending") would be wrong. A never-checked side is treated the same: nothing to carry.
        boolean sameMonthAsLastCheck = state.checkedAt() != null
                && YearMonth.from(state.checkedAt()).equals(YearMonth.from(today));
        int alreadyImportedCount = sameMonthAsLastCheck ? state.imported() : 0;
        boolean knownPending = sameMonthAsLastCheck && state.totalSieg() > 0 && state.imported() < state.totalSieg();

        FiscalXmlSyncResult result = provider.sync(new FiscalXmlSyncRequest(
                company.getId(), today.withDayOfMonth(1), today, alreadyImportedCount, knownPending, side));
        log.info("Fiscal XML sync for company {} ({}): {} new, {} duplicated, {} parse errors",
                company.getId(), side, result.persistedNew(), result.duplicated(), result.parseErrors());
    }

    /** One side's bookkeeping, read from its own columns on the control row (null = never checked). */
    record SideState(int totalSieg, int imported, LocalDateTime checkedAt) {

        static SideState of(FiscalXmlSyncControl control, FiscalXmlSide side) {
            if (side == FiscalXmlSide.ISSUED) {
                return new SideState(valueOf(control.getIssuedTotalSieg()), valueOf(control.getIssuedTotalImported()),
                        control.getIssuedCheckedAt());
            }
            return new SideState(control.getTotalSieg(), control.getTotalImportado(), control.getUltimaVerificacaoEm());
        }

        /** Everything the provider reported is already imported, checked today - no request needed. */
        boolean isUpToDate(LocalDate today) {
            return totalSieg > 0 && imported >= totalSieg && checkedAt != null && checkedAt.toLocalDate().isEqual(today);
        }

        private static int valueOf(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
