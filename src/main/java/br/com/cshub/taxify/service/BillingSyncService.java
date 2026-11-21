package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.billing.ExternalLink;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.dto.billing.BillingSyncResultDTO;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.billing.BillingProvider;
import br.com.cshub.taxify.integration.billing.BillingProviderRegistry;
import br.com.cshub.taxify.integration.billing.ChargeQuery;
import br.com.cshub.taxify.integration.billing.ExternalCharge;
import br.com.cshub.taxify.integration.billing.ExternalContract;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.ExternalLinkRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brings a firm's contracts and fee charges from its billing provider into Taxify - knows
 * only the {@link BillingProvider} port, never a specific system:
 * <ol>
 *   <li>links each company to the provider's customer with the same CNPJ/CPF (once);</li>
 *   <li>copies the customer's active contract onto the company's current subscription
 *   (contract id/number, status, next due date) - several active ones: the most recent wins
 *   and the subscription gets a warning;</li>
 *   <li>upserts the customer's receivables in the fee categories as BillingCharges;</li>
 *   <li>sets the subscription fee to the most recent charge (the contract itself may carry
 *   no value, as in Conta Azul).</li>
 * </ol>
 * Not one transaction: each save commits on its own, so a failure on one company doesn't
 * undo the others (the run reports it in {@code errors}).
 */
@Service
public class BillingSyncService {

    private static final Logger log = LoggerFactory.getLogger(BillingSyncService.class);
    /** First run (and full runs) look this far back... */
    private static final int HISTORY_MONTHS = 13;
    /** ...and this far ahead (charges already generated for the coming months). */
    private static final int AHEAD_MONTHS = 3;
    /** A charge due later than this isn't "the current fee" yet. */
    private static final int FEE_LOOKAHEAD_DAYS = 45;

    private final BillingProviderRegistry registry;
    private final FirmIntegrationService firmIntegrationService;
    private final AccountingFirmRepository accountingFirmRepository;
    private final CompanyRepository companyRepository;
    private final ExternalLinkRepository linkRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingChargeRepository chargeRepository;
    private final MessageService messageService;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public BillingSyncService(BillingProviderRegistry registry, FirmIntegrationService firmIntegrationService,
                              AccountingFirmRepository accountingFirmRepository, CompanyRepository companyRepository,
                              ExternalLinkRepository linkRepository, SubscriptionRepository subscriptionRepository,
                              BillingChargeRepository chargeRepository, MessageService messageService) {
        this.registry = registry;
        this.firmIntegrationService = firmIntegrationService;
        this.accountingFirmRepository = accountingFirmRepository;
        this.companyRepository = companyRepository;
        this.linkRepository = linkRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.chargeRepository = chargeRepository;
        this.messageService = messageService;
    }

    /** "Sincronizar agora" - full window, for the current user's firm. */
    public BillingSyncResultDTO syncCurrentFirm() {
        AccountingFirm firm = accountingFirmRepository.findById(firmIntegrationService.currentFirmId())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.accountingFirm.not.found")));
        return syncFirm(firm, false);
    }

    /**
     * @param incremental only charges changed since the last sync (a firm never synced still gets a full run)
     */
    public BillingSyncResultDTO syncFirm(AccountingFirm firm, boolean incremental) {
        BillingProviderType providerType = firm.getBillingProvider();
        if (providerType == BillingProviderType.MANUAL) {
            throw new BadRequestException(messageService.getMessage("error.billing.provider.manual"));
        }
        BillingProvider provider = registry.find(providerType)
                .filter(candidate -> candidate.isConnected(firm))
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.billing.provider.not.connected")));
        if (!running.add(firm.getId())) {
            throw new ConflictException(messageService.getMessage("error.billing.sync.running"));
        }
        try {
            return run(firm, provider, incremental);
        } finally {
            running.remove(firm.getId());
        }
    }

    private BillingSyncResultDTO run(AccountingFirm firm, BillingProvider provider, boolean incremental) {
        LocalDateTime startedAt = LocalDateTime.now();
        LocalDate today = startedAt.toLocalDate();
        BillingProviderType type = provider.type();
        FirmIntegration integration = firmIntegrationService.getOrCreate(firm.getId(), type.integrationType());
        Run result = new Run();

        // 1. links company <-> provider customer
        Map<UUID, ExternalLink> links = new HashMap<>();
        linkRepository.findByCompany_AccountingFirm_IdAndProviderAndDeletedIsFalse(firm.getId(), type)
                .forEach(link -> links.put(link.getCompany().getId(), link));
        List<Company> companies = companyRepository.findByAccountingFirm_Id(firm.getId()).stream()
                .filter(company -> !company.isDeleted())
                .toList();
        result.companies = companies.size();
        for (Company company : companies) {
            if (links.containsKey(company.getId())) {
                continue;
            }
            try {
                String document = company.getDocument() == null ? null : company.getDocument().getNumber();
                provider.findCustomerByDocument(firm, document).ifPresentOrElse(customer -> {
                    links.put(company.getId(), linkRepository.save(new ExternalLink(company, type, customer.id(), customer.name())));
                    result.newlyLinked++;
                }, () -> result.unmatched.add(label(company)));
            } catch (RuntimeException e) {
                log.warn("Billing link failed for company {} of firm {}", company.getId(), firm.getId(), e);
                result.errors.add(label(company) + ": " + e.getMessage());
            }
        }
        result.linked = links.size();

        // 2. contracts -> current subscriptions
        Map<String, Company> companyByCustomer = new HashMap<>();
        Map<UUID, Subscription> subscriptionByCompany = new HashMap<>();
        for (Company company : companies) {
            ExternalLink link = links.get(company.getId());
            if (link == null) {
                continue;
            }
            companyByCustomer.put(link.getExternalId(), company);
            try {
                Optional<Subscription> current = currentSubscription(company.getId(), today);
                if (current.isEmpty()) {
                    result.withoutSubscription.add(label(company));
                    continue;
                }
                Subscription subscription = current.get();
                applyContract(subscription, provider.listContracts(firm, link.getExternalId()), type, startedAt);
                if (subscription.getSyncWarning() != null) {
                    result.warnings.add(label(company) + ": " + subscription.getSyncWarning());
                }
                subscriptionByCompany.put(company.getId(), subscriptionRepository.save(subscription));
                result.subscriptionsUpdated++;
            } catch (RuntimeException e) {
                log.warn("Billing contract sync failed for company {} of firm {}", company.getId(), firm.getId(), e);
                result.errors.add(label(company) + ": " + e.getMessage());
            }
        }

        // 3. charges
        if (!companyByCustomer.isEmpty()) {
            LocalDateTime changedSince = incremental ? integration.getLastSyncAt() : null;
            ChargeQuery query = new ChargeQuery(List.copyOf(companyByCustomer.keySet()), feeCategoryIds(integration),
                    today.minusMonths(HISTORY_MONTHS).withDayOfMonth(1), today.plusMonths(AHEAD_MONTHS), changedSince);
            try {
                for (ExternalCharge external : provider.listCharges(firm, query)) {
                    Company company = companyByCustomer.get(external.customerId());
                    if (company == null) {
                        continue;
                    }
                    upsertCharge(company, subscriptionByCompany.get(company.getId()), type, external, startedAt);
                    result.chargesSynced++;
                }
            } catch (RuntimeException e) {
                log.warn("Billing charges sync failed for firm {}", firm.getId(), e);
                result.errors.add(e.getMessage());
            }
        }

        // 4. fee = most recent charge
        for (Subscription subscription : subscriptionByCompany.values()) {
            chargeRepository.findFirstByCompany_IdAndSourceAndStatusNotInAndDueDateLessThanEqualAndDeletedIsFalseOrderByDueDateDesc(
                            subscription.getCompany().getId(), type, List.of(ChargeStatus.CANCELED), today.plusDays(FEE_LOOKAHEAD_DAYS))
                    .ifPresent(charge -> {
                        subscription.setFee(charge.getAmount());
                        subscriptionRepository.save(subscription);
                    });
        }

        if (result.errors.isEmpty()) {
            firmIntegrationService.markSynced(firm.getId(), type.integrationType(), startedAt);
        }
        log.info("Billing sync ({}) for firm {}: {} companies, {} linked ({} new), {} unmatched, {} subscriptions, {} charges, {} errors",
                type, firm.getId(), result.companies, result.linked, result.newlyLinked, result.unmatched.size(),
                result.subscriptionsUpdated, result.chargesSynced, result.errors.size());
        return result.toDTO(startedAt);
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
            subscription.setStatus(contracts.isEmpty() ? subscription.getStatus() : SubscriptionStatus.INACTIVE);
            subscription.setSyncWarning(messageService.getMessage("billing.sync.warning.no.contract"));
            return;
        }
        ExternalContract contract = active.getFirst();
        subscription.setExternalContractId(contract.id());
        subscription.setExternalContractNumber(contract.number());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setNextDueDate(contract.nextDueDate());
        if (contract.amount() != null) {
            subscription.setFee(contract.amount());
        }
        if (contract.cycle() != null) {
            subscription.setBillingCycle(contract.cycle());
        }
        if (contract.nextDueDate() != null) {
            subscription.setBillingDay(contract.nextDueDate().getDayOfMonth());
        }
        subscription.setSyncWarning(active.size() > 1
                ? messageService.getMessage("billing.sync.warning.multiple.contracts", String.valueOf(active.size()), String.valueOf(contract.number()))
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
        charge.setDescription(truncate(external.description(), 500));
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

    /** OPEN past its due date is OVERDUE even if the provider hasn't flipped it yet. */
    static ChargeStatus effectiveStatus(ExternalCharge charge) {
        ChargeStatus status = charge.status() == null ? ChargeStatus.OPEN : charge.status();
        if ((status == ChargeStatus.OPEN || status == ChargeStatus.PARTIALLY_PAID)
                && charge.dueDate() != null && charge.dueDate().isBefore(LocalDate.now())) {
            return ChargeStatus.OVERDUE;
        }
        return status;
    }

    private Optional<Subscription> currentSubscription(UUID companyId, LocalDate today) {
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(companyId, today, today);
    }

    static List<String> feeCategoryIds(FirmIntegration integration) {
        String raw = integration.getFeeCategoryIds();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(id -> !id.isEmpty()).toList();
    }

    private static String label(Company company) {
        return company.getName() != null ? company.getName() : String.valueOf(company.getId());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static final class Run {
        int companies;
        int linked;
        int newlyLinked;
        int subscriptionsUpdated;
        int chargesSynced;
        final List<String> unmatched = new ArrayList<>();
        final List<String> withoutSubscription = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        BillingSyncResultDTO toDTO(LocalDateTime startedAt) {
            return new BillingSyncResultDTO(startedAt, companies, linked, newlyLinked, unmatched, subscriptionsUpdated,
                    withoutSubscription, chargesSynced, warnings, errors);
        }
    }
}
