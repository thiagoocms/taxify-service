package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.billing.ExternalLink;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.dto.billing.BillingSyncResultDTO;
import br.com.cshub.taxify.dto.integration.SyncJobStatusDTO;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;
import br.com.cshub.taxify.integration.billing.BillingProvider;
import br.com.cshub.taxify.integration.billing.BillingProviderRegistry;
import br.com.cshub.taxify.integration.billing.ChargeQuery;
import br.com.cshub.taxify.integration.billing.ExternalCharge;
import br.com.cshub.taxify.integration.billing.ExternalContract;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.ExternalLinkRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.job.IntegrationJobRunner;
import br.com.cshub.taxify.service.job.SyncProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brings a firm's contracts and fee charges from its billing provider into Taxify - knows
 * only the {@link BillingProvider} port, never a specific system. One run:
 * <ol>
 *   <li>per company, in parallel ({@link #COMPANY_PARALLELISM} at a time - the provider
 *   rate-limits): link it to the provider's customer with the same CNPJ/CPF (once), then copy
 *   that customer's active contract onto the company's current subscription (several
 *   active: the most recent wins, with a warning) - see {@link #syncCompany};</li>
 *   <li>{@link #syncCharges} - the linked customers' receivables in the fee categories,
 *   batched, as BillingCharges;</li>
 *   <li>{@link #refreshFees} - the subscription fee = the most recent charge (the contract may carry no value).</li>
 * </ol>
 * Runs in the background through {@link IntegrationJobRunner} ({@link #startForCurrentFirm}),
 * which also prevents overlapping runs. Not one transaction: each save commits on its own,
 * so a failure on one company doesn't undo the others - the run reports it in {@code errors}
 * and doesn't mark the firm synced.
 */
@Service
public class BillingSyncService {

    private static final Logger log = LoggerFactory.getLogger(BillingSyncService.class);

    /** Companies processed at the same time within one firm (provider API calls in flight). */
    static final int COMPANY_PARALLELISM = 3;
    /** A full run looks this far back... */
    private static final int HISTORY_MONTHS = 13;
    /** ...and this far ahead (charges already generated for the coming months). */
    private static final int AHEAD_MONTHS = 3;
    /** A charge due later than this isn't "the current fee" yet. */
    private static final int FEE_LOOKAHEAD_DAYS = 45;
    private static final int DESCRIPTION_MAX = 500;

    private final BillingProviderRegistry registry;
    private final CurrentFirmService currentFirmService;
    private final FirmIntegrationService firmIntegrationService;
    private final IntegrationJobRunner jobRunner;
    private final CompanyRepository companyRepository;
    private final ExternalLinkRepository linkRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingChargeRepository chargeRepository;
    private final MessageService messageService;

    public BillingSyncService(BillingProviderRegistry registry, CurrentFirmService currentFirmService,
                              FirmIntegrationService firmIntegrationService, IntegrationJobRunner jobRunner,
                              CompanyRepository companyRepository, ExternalLinkRepository linkRepository,
                              SubscriptionRepository subscriptionRepository, BillingChargeRepository chargeRepository,
                              MessageService messageService) {
        this.registry = registry;
        this.currentFirmService = currentFirmService;
        this.firmIntegrationService = firmIntegrationService;
        this.jobRunner = jobRunner;
        this.companyRepository = companyRepository;
        this.linkRepository = linkRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.chargeRepository = chargeRepository;
        this.messageService = messageService;
    }

    // ------------------------------------------------------------------ background job

    /** "Sincronizar agora": full window, in the background - returns RUNNING right away. */
    public SyncJobStatusDTO<BillingSyncResultDTO> startForCurrentFirm() {
        return start(currentFirmService.require(), false);
    }

    /** Validates up front (MANUAL / not connected fail now, not inside the job), then starts it. */
    public SyncJobStatusDTO<BillingSyncResultDTO> start(AccountingFirm firm, boolean incremental) {
        BillingProvider provider = registry.requireConnected(firm);
        return jobRunner.start(firm.getId(), provider.type().integrationType(), BillingSyncResultDTO.class,
                progress -> syncFirm(firm, incremental, progress));
    }

    public SyncJobStatusDTO<BillingSyncResultDTO> statusForCurrentFirm() {
        AccountingFirm firm = currentFirmService.require();
        if (firm.getBillingProvider().integrationType() == null) {
            return new SyncJobStatusDTO<>(null, null, null, null, null, null, null, null);
        }
        return jobRunner.status(firm.getId(), firm.getBillingProvider().integrationType(), BillingSyncResultDTO.class);
    }

    // ------------------------------------------------------------------ the sync itself

    /**
     * Synchronous run - what the job executes.
     *
     * @param incremental only charges changed since the last sync (a firm never synced still gets a full run)
     */
    public BillingSyncResultDTO syncFirm(AccountingFirm firm, boolean incremental, SyncProgress progress) {
        SyncRun run = new SyncRun(firm, registry.requireConnected(firm), LocalDateTime.now());
        FirmIntegration integration = firmIntegrationService.getOrCreate(firm.getId(), run.type.integrationType());
        List<Company> companies = companyRepository.findByAccountingFirm_Id(firm.getId()).stream()
                .filter(company -> !company.isDeleted())
                .toList();
        run.companies = companies.size();
        progress.total(companies.size());

        Map<UUID, ExternalLink> links = new ConcurrentHashMap<>();
        linkRepository.findByCompany_AccountingFirm_IdAndProviderAndDeletedIsFalse(firm.getId(), run.type)
                .forEach(link -> links.put(link.getCompany().getId(), link));
        Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(COMPANY_PARALLELISM,
                Thread.ofPlatform().name("billing-sync-", 0).daemon().factory())) {
            for (Company company : companies) {
                pool.execute(() -> {
                    run.forCompany(company, () -> syncCompany(run, company, links, subscriptions));
                    progress.advance();
                });
            }
        } // close() waits for every company
        run.linked = links.size();

        Map<String, Company> companyByCustomer = new ConcurrentHashMap<>();
        companies.stream()
                .filter(company -> links.containsKey(company.getId()))
                .forEach(company -> companyByCustomer.put(links.get(company.getId()).getExternalId(), company));
        syncCharges(run, integration, companyByCustomer, subscriptions, incremental);
        refreshFees(run, subscriptions.values());

        if (run.errors.isEmpty()) {
            firmIntegrationService.markSynced(firm.getId(), run.type.integrationType(), run.startedAt);
        }
        log.info("Billing sync ({}) for firm {}: {} companies, {} linked ({} new), {} unmatched, {} subscriptions, {} charges, {} errors",
                run.type, firm.getId(), run.companies, run.linked, run.newlyLinked.get(), run.unmatched.size(),
                run.subscriptionsUpdated.get(), run.chargesSynced, run.errors.size());
        return run.toResult();
    }

    /** One company: link by document if needed, then its contract onto the current subscription. */
    private void syncCompany(SyncRun run, Company company, Map<UUID, ExternalLink> links, Map<UUID, Subscription> subscriptions) {
        ExternalLink link = links.get(company.getId());
        if (link == null) {
            String document = company.getDocument() == null ? null : company.getDocument().getNumber();
            Optional<ExternalLink> found = run.provider.findCustomerByDocument(run.firm, document)
                    .map(customer -> linkRepository.save(new ExternalLink(company, run.type, customer.id(), customer.name())));
            if (found.isEmpty()) {
                run.unmatched.add(label(company));
                return;
            }
            link = found.get();
            links.put(company.getId(), link);
            run.newlyLinked.incrementAndGet();
        }

        Optional<Subscription> current = currentSubscription(company.getId(), run.today());
        if (current.isEmpty()) {
            run.withoutSubscription.add(label(company));
            return;
        }
        Subscription subscription = current.get();
        applyContract(subscription, run.provider.listContracts(run.firm, link.getExternalId()), run.type, run.startedAt);
        if (subscription.getSyncWarning() != null) {
            run.warnings.add(label(company) + ": " + subscription.getSyncWarning());
        }
        subscriptions.put(company.getId(), subscriptionRepository.save(subscription));
        run.subscriptionsUpdated.incrementAndGet();
    }

    private void syncCharges(SyncRun run, FirmIntegration integration, Map<String, Company> companyByCustomer,
                             Map<UUID, Subscription> subscriptions, boolean incremental) {
        if (companyByCustomer.isEmpty()) {
            return;
        }
        LocalDate today = run.today();
        ChargeQuery query = new ChargeQuery(
                List.copyOf(companyByCustomer.keySet()),
                integration.getFeeCategoryIdList(),
                today.minusMonths(HISTORY_MONTHS).withDayOfMonth(1),
                today.plusMonths(AHEAD_MONTHS),
                incremental ? integration.getLastSyncAt() : null);
        try {
            for (ExternalCharge external : run.provider.listCharges(run.firm, query)) {
                Company company = companyByCustomer.get(external.customerId());
                if (company != null) {
                    upsertCharge(company, subscriptions.get(company.getId()), run.type, external, run.startedAt);
                    run.chargesSynced++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Billing charges sync failed for firm {}", run.firm.getId(), e);
            run.errors.add(e.getMessage());
        }
    }

    private void refreshFees(SyncRun run, Iterable<Subscription> subscriptions) {
        LocalDate feeDueUntil = run.today().plusDays(FEE_LOOKAHEAD_DAYS);
        for (Subscription subscription : subscriptions) {
            latestCharge(subscription.getCompany().getId(), run.type, feeDueUntil).ifPresent(charge -> {
                subscription.setFee(charge.getAmount());
                subscriptionRepository.save(subscription);
            });
        }
    }

    /** Copies the provider's active contract onto the subscription (most recent one if several). */
    void applyContract(Subscription subscription, List<ExternalContract> contracts, BillingProviderType type, LocalDateTime syncedAt) {
        List<ExternalContract> active = contracts.stream()
                .filter(contract -> contract.status() == SubscriptionStatus.ACTIVE)
                .sorted(Comparator.comparing(ExternalContract::startDate, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
        subscription.setSource(type);
        subscription.setLastSyncedAt(syncedAt);
        if (active.isEmpty()) {
            if (!contracts.isEmpty()) {
                subscription.setStatus(SubscriptionStatus.INACTIVE);
            }
            subscription.setSyncWarning(messageService.getMessage("billing.sync.warning.no.contract"));
            return;
        }

        ExternalContract contract = active.getFirst();
        subscription.setExternalContractId(contract.id());
        subscription.setExternalContractNumber(contract.number());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setNextDueDate(contract.nextDueDate());
        Optional.ofNullable(contract.amount()).ifPresent(subscription::setFee);
        Optional.ofNullable(contract.cycle()).ifPresent(subscription::setBillingCycle);
        Optional.ofNullable(contract.nextDueDate()).map(LocalDate::getDayOfMonth).ifPresent(subscription::setBillingDay);
        subscription.setSyncWarning(active.size() > 1
                ? messageService.getMessage("billing.sync.warning.multiple.contracts", String.valueOf(active.size()), contract.number())
                : null);
    }

    private void upsertCharge(Company company, Subscription subscription, BillingProviderType type, ExternalCharge external,
                              LocalDateTime syncedAt) {
        BillingCharge charge = chargeRepository.findFirstByCompany_IdAndSourceAndExternalId(company.getId(), type, external.id())
                .orElseGet(BillingCharge::new);
        charge.setCompany(company);
        if (subscription != null || charge.getSubscription() == null) {
            charge.setSubscription(subscription);
        }
        charge.setSource(type);
        charge.setExternalId(external.id());
        charge.setDescription(truncate(external.description()));
        charge.setCompetenceDate(external.competenceDate());
        charge.setDueDate(external.dueDate());
        charge.setAmount(external.amount() == null ? BigDecimal.ZERO : external.amount());
        charge.setPaidAmount(external.paidAmount());
        charge.setOpenAmount(external.openAmount());
        charge.setStatus(effectiveStatus(external));
        charge.setLastSyncedAt(syncedAt);
        charge.setDeleted(false);
        chargeRepository.save(charge);
    }

    /** OPEN (or partially paid) past its due date is OVERDUE even if the provider hasn't flipped it yet. */
    static ChargeStatus effectiveStatus(ExternalCharge charge) {
        ChargeStatus status = charge.status() == null ? ChargeStatus.OPEN : charge.status();
        boolean pastDue = charge.dueDate() != null && charge.dueDate().isBefore(LocalDate.now());
        return pastDue && (status == ChargeStatus.OPEN || status == ChargeStatus.PARTIALLY_PAID) ? ChargeStatus.OVERDUE : status;
    }

    private Optional<Subscription> currentSubscription(UUID companyId, LocalDate today) {
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(companyId, today, today);
    }

    private Optional<BillingCharge> latestCharge(UUID companyId, BillingProviderType type, LocalDate dueUntil) {
        return chargeRepository.findFirstByCompany_IdAndSourceAndStatusNotInAndDueDateLessThanEqualAndDeletedIsFalseOrderByDueDateDesc(
                companyId, type, List.of(ChargeStatus.CANCELED), dueUntil);
    }

    private static String label(Company company) {
        return company.getName() != null ? company.getName() : String.valueOf(company.getId());
    }

    private static String truncate(String value) {
        return value == null || value.length() <= DESCRIPTION_MAX ? value : value.substring(0, DESCRIPTION_MAX);
    }

    /** State of one run - shared by the company workers, hence the thread-safe counters and lists. */
    private static final class SyncRun {
        final AccountingFirm firm;
        final BillingProvider provider;
        final BillingProviderType type;
        final LocalDateTime startedAt;
        int companies;
        int linked;
        int chargesSynced;
        final AtomicInteger newlyLinked = new AtomicInteger();
        final AtomicInteger subscriptionsUpdated = new AtomicInteger();
        final List<String> unmatched = Collections.synchronizedList(new ArrayList<>());
        final List<String> withoutSubscription = Collections.synchronizedList(new ArrayList<>());
        final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        SyncRun(AccountingFirm firm, BillingProvider provider, LocalDateTime startedAt) {
            this.firm = firm;
            this.provider = provider;
            this.type = provider.type();
            this.startedAt = startedAt;
        }

        LocalDate today() {
            return startedAt.toLocalDate();
        }

        /** Runs one company's step; a failure is recorded against that company and the run goes on. */
        void forCompany(Company company, Runnable step) {
            try {
                step.run();
            } catch (RuntimeException e) {
                log.warn("Billing sync failed for company {} of firm {}", company.getId(), firm.getId(), e);
                errors.add(label(company) + ": " + e.getMessage());
            }
        }

        BillingSyncResultDTO toResult() {
            return new BillingSyncResultDTO(startedAt, companies, linked, newlyLinked.get(), sorted(unmatched),
                    subscriptionsUpdated.get(), sorted(withoutSubscription), chargesSynced, sorted(warnings), sorted(errors));
        }

        /** Workers finish in any order - sort so the summary reads the same every run. */
        private static List<String> sorted(List<String> values) {
            synchronized (values) {
                return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }
        }
    }
}
