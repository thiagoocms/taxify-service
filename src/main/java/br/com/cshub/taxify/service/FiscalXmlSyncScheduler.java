package br.com.cshub.taxify.service;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
 * page every tick. {@link #ensureControlRowsExist()} keeps that table complete by
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
 * <p>Only companies entitled to {@link PlanServiceEnum#FISCAL_XML_IMPORT} are actually
 * synced: {@link #hasFiscalXmlImportEntitlement} requires a currently "vigente" (active -
 * {@code startDate <= today <= endDate}) {@link Subscription} whose {@link
 * br.com.cshub.taxify.domain.plan.Plan} includes that service. {@link
 * #ensureControlRowsExist()} still seeds a control row for every company regardless -
 * the control table stays a complete picture of every company, it's only the actual
 * provider call that's gated by the plan.
 *
 * <p>A provider's rate limit is account-wide, not per company (SIEG: 2 requests/minute
 * total), so once there are more companies than that limit allows per tick, trying to
 * sync everyone in a single run would block this thread for minutes at a time. Instead,
 * each tick only syncs {@link FiscalXmlSyncProperties#getBatchSize()} companies, picked
 * round-robin from a rotating cursor so every company gets a turn over successive ticks
 * - {@code cron} is meant to fire often (each tick does bounded, fast work) rather than
 * rarely-but-long-running. The cursor lives in memory only: it resets to the start on
 * restart, which just means a partial extra cycle, not a correctness problem.
 */
@Component
public class FiscalXmlSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlSyncScheduler.class);

    private final CompanyRepository companyRepository;
    private final FiscalXmlSyncControlRepository syncControlRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final Map<String, FiscalXmlProvider> providers;
    private final FiscalXmlSyncProperties properties;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public FiscalXmlSyncScheduler(CompanyRepository companyRepository, FiscalXmlSyncControlRepository syncControlRepository,
                                   SubscriptionRepository subscriptionRepository, Map<String, FiscalXmlProvider> providers,
                                   FiscalXmlSyncProperties properties) {
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

        List<FiscalXmlSyncControl> batch = nextBatch(controls, properties.getBatchSize());
        log.info("Starting scheduled fiscal XML sync (provider={}): {} of {} entitled companies this tick",
                properties.getProvider(), batch.size(), controls.size());

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
     * subscription lists {@link PlanServiceEnum#FISCAL_XML_IMPORT} among its services. No
     * active subscription, or an active one on a plan without this service, both mean
     * "not entitled" - the company is simply left out of the tick (its control row is
     * untouched, not deleted or flagged).
     */
    private boolean hasFiscalXmlImportEntitlement(Company company, LocalDate today) {
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        company.getId(), today, today)
                .map(Subscription::getPlan)
                .map(plan -> plan.getServices().contains(PlanServiceEnum.FISCAL_XML_IMPORT))
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
                control.setUltimaVerificacaoEm(LocalDateTime.now());
                syncControlRepository.save(control);
            }
        }
    }

    /**
     * Picks the next {@code batchSize} controls starting at the rotating cursor,
     * wrapping around to the front of the list when it runs past the end, and advances
     * the cursor for the following tick. Re-reads {@code controls.size()} every call, so
     * it tolerates companies being added/removed between ticks (at worst some skew in
     * whose "turn" it is, never an out-of-bounds access).
     */
    private List<FiscalXmlSyncControl> nextBatch(List<FiscalXmlSyncControl> controls, int batchSize) {
        int size = controls.size();
        int effectiveBatchSize = Math.min(Math.max(batchSize, 1), size);
        int start = cursor.get() % size;

        List<FiscalXmlSyncControl> batch = new ArrayList<>(effectiveBatchSize);
        for (int i = 0; i < effectiveBatchSize; i++) {
            batch.add(controls.get((start + i) % size));
        }
        cursor.set((start + effectiveBatchSize) % size);
        return batch;
    }

    private void syncCompany(FiscalXmlProvider provider, FiscalXmlSyncControl control, LocalDate today) {
        Company company = control.getCompany();

        if (isAlreadyUpToDateToday(control, today)) {
            log.info("Company {} already fully synced for {} ({} of {} imported) - skipping, no request spent",
                    company.getId(), today, control.getTotalImportado(), control.getTotalSieg());
            return;
        }

        LocalDate start = today.withDayOfMonth(1);

        // totalImportado/totalSieg on the control row refer to whichever window was last
        // checked - once the calendar month rolls over, that window is last month's, so
        // reusing its totalImportado as this month's skip (or its totalSieg to claim
        // "known pending") would be wrong. Null-safe: a never-checked control (no
        // ultimaVerificacaoEm yet) is treated the same as "different month" - nothing
        // reliable to carry forward.
        boolean sameMonthAsLastCheck = control.getUltimaVerificacaoEm() != null
                && YearMonth.from(control.getUltimaVerificacaoEm()).equals(YearMonth.from(today));
        int alreadyImportedCount = sameMonthAsLastCheck ? control.getTotalImportado() : 0;
        boolean knownPending = sameMonthAsLastCheck
                && control.getTotalSieg() > 0 && control.getTotalImportado() < control.getTotalSieg();

        FiscalXmlSyncResult result = provider.sync(
                new FiscalXmlSyncRequest(company.getId(), start, today, alreadyImportedCount, knownPending));
        log.info("Fiscal XML sync for company {}: {} new, {} duplicated, {} parse errors",
                company.getId(), result.persistedNew(), result.duplicated(), result.parseErrors());
    }

    /**
     * Once {@code totalImportado} catches up to {@code totalSieg} on a given day, there's
     * nothing new to find by asking again the same day - so this skips the request
     * entirely rather than spending part of the provider's rate limit (SIEG: 2/min,
     * account-wide) confirming what's already known. {@code totalSieg == 0} never counts
     * as "up to date" here: that's "never successfully checked yet" as much as it is "no
     * pending notes", so it's always worth a real check. The next calendar day this stops
     * matching (new {@code today}), so the following tick checks again - new notes for
     * the new day, or ones issued for a past day but reported by SIEG only later, aren't
     * missed.
     */
    private boolean isAlreadyUpToDateToday(FiscalXmlSyncControl control, LocalDate today) {
        return control.getTotalSieg() > 0
                && control.getTotalImportado() >= control.getTotalSieg()
                && control.getUltimaVerificacaoEm() != null
                && control.getUltimaVerificacaoEm().toLocalDate().isEqual(today);
    }
}
