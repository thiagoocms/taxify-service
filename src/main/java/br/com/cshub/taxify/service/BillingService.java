package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.billing.ExternalLink;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.billing.BillingCategoryDTO;
import br.com.cshub.taxify.dto.billing.BillingChargeDTO;
import br.com.cshub.taxify.dto.billing.BillingSettingsDTO;
import br.com.cshub.taxify.dto.billing.BillingSettingsUpdateDTO;
import br.com.cshub.taxify.dto.billing.CompanyBillingDTO;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.billing.BillingProviderRegistry;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.ExternalLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Billing settings of the current firm and the billing view of one company. */
@Service
public class BillingService {

    private static final Set<ChargeStatus> OPEN_STATUSES = EnumSet.of(ChargeStatus.OPEN, ChargeStatus.PARTIALLY_PAID);

    private final BillingProviderRegistry registry;
    private final CurrentFirmService currentFirmService;
    private final FirmIntegrationService firmIntegrationService;
    private final AccountingFirmRepository accountingFirmRepository;
    private final CompanyRepository companyRepository;
    private final ExternalLinkRepository linkRepository;
    private final BillingChargeRepository chargeRepository;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;

    public BillingService(BillingProviderRegistry registry, CurrentFirmService currentFirmService,
                          FirmIntegrationService firmIntegrationService, AccountingFirmRepository accountingFirmRepository,
                          CompanyRepository companyRepository, ExternalLinkRepository linkRepository,
                          BillingChargeRepository chargeRepository, TenantAccessService tenantAccessService,
                          MessageService messageService) {
        this.registry = registry;
        this.currentFirmService = currentFirmService;
        this.firmIntegrationService = firmIntegrationService;
        this.accountingFirmRepository = accountingFirmRepository;
        this.companyRepository = companyRepository;
        this.linkRepository = linkRepository;
        this.chargeRepository = chargeRepository;
        this.tenantAccessService = tenantAccessService;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public BillingSettingsDTO getSettings() {
        return toSettings(currentFirmService.require());
    }

    @Transactional
    public BillingSettingsDTO updateSettings(BillingSettingsUpdateDTO update) {
        BillingProviderType provider = update.provider();
        if (provider != BillingProviderType.MANUAL && registry.find(provider).isEmpty()) {
            throw new BadRequestException(messageService.getMessage("error.billing.provider.not.connected"));
        }
        AccountingFirm firm = currentFirmService.require();
        firm.setBillingProvider(provider);
        accountingFirmRepository.save(firm);

        IntegrationType integrationType = provider.integrationType();
        if (integrationType != null) {
            FirmIntegration integration = firmIntegrationService.getOrCreate(firm.getId(), integrationType);
            integration.setFeeCategoryIdList(update.feeCategoryIds());
            firmIntegrationService.save(integration);
        }
        return toSettings(firm);
    }

    /** Revenue categories of the chosen provider, for the "which ones are fees" picker. */
    public List<BillingCategoryDTO> listCategories() {
        AccountingFirm firm = currentFirmService.require();
        return registry.requireConnected(firm).listRevenueCategories(firm).stream()
                .map(category -> new BillingCategoryDTO(category.id(), category.name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyBillingDTO getCompanyBilling(UUID companyId) {
        Company company = companyRepository.findFirstByIdAndDeletedIsFalse(companyId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.entity.not.found")));
        tenantAccessService.assertCompanyAccess(company);

        BillingProviderType provider = company.getAccountingFirm().getBillingProvider();
        Optional<ExternalLink> link = provider == BillingProviderType.MANUAL
                ? Optional.empty()
                : linkRepository.findFirstByCompany_IdAndProviderAndDeletedIsFalse(companyId, provider);
        List<BillingCharge> charges = chargeRepository.findByCompany_IdAndDeletedIsFalseOrderByDueDateDesc(companyId);

        return new CompanyBillingDTO(
                provider,
                link.map(ExternalLink::getExternalId).orElse(null),
                link.map(ExternalLink::getExternalName).orElse(null),
                sumOpenAmount(charges, OPEN_STATUSES),
                sumOpenAmount(charges, EnumSet.of(ChargeStatus.OVERDUE)),
                charges.stream().filter(charge -> charge.getStatus() == ChargeStatus.OVERDUE).count(),
                charges.stream().map(BillingService::toDTO).toList());
    }

    private BillingSettingsDTO toSettings(AccountingFirm firm) {
        BillingProviderType provider = firm.getBillingProvider();
        IntegrationType integrationType = provider.integrationType();
        if (integrationType == null) {
            return new BillingSettingsDTO(provider, registry.selectable(), true, List.of(), null);
        }
        Optional<FirmIntegration> integration = firmIntegrationService.find(firm.getId(), integrationType);
        return new BillingSettingsDTO(provider, registry.selectable(), registry.isConnected(firm),
                integration.map(FirmIntegration::getFeeCategoryIdList).orElse(List.of()),
                integration.map(FirmIntegration::getLastSyncAt).orElse(null));
    }

    private static BigDecimal sumOpenAmount(List<BillingCharge> charges, Set<ChargeStatus> statuses) {
        return charges.stream()
                .filter(charge -> statuses.contains(charge.getStatus()))
                .map(charge -> charge.getOpenAmount() == null ? BigDecimal.ZERO : charge.getOpenAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BillingChargeDTO toDTO(BillingCharge charge) {
        return new BillingChargeDTO(
                charge.getId().toString(),
                charge.getCompany().getId().toString(),
                charge.getSubscription() == null ? null : charge.getSubscription().getId().toString(),
                charge.getSource(),
                charge.getDescription(),
                charge.getCompetenceDate(),
                charge.getDueDate(),
                charge.getAmount(),
                charge.getPaidAmount(),
                charge.getOpenAmount(),
                charge.getStatus());
    }
}
