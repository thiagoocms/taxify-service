package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.billing.BillingCategoryDTO;
import br.com.cshub.taxify.dto.billing.BillingChargeDTO;
import br.com.cshub.taxify.dto.billing.BillingSettingsDTO;
import br.com.cshub.taxify.dto.billing.BillingSettingsUpdateDTO;
import br.com.cshub.taxify.dto.billing.CompanyBillingDTO;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.billing.BillingProvider;
import br.com.cshub.taxify.integration.billing.BillingProviderRegistry;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.ExternalLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Billing settings of the current firm and the billing view of one company. */
@Service
public class BillingService {

    private final BillingProviderRegistry registry;
    private final FirmIntegrationService firmIntegrationService;
    private final AccountingFirmRepository accountingFirmRepository;
    private final CompanyRepository companyRepository;
    private final ExternalLinkRepository linkRepository;
    private final BillingChargeRepository chargeRepository;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;

    public BillingService(BillingProviderRegistry registry, FirmIntegrationService firmIntegrationService,
                          AccountingFirmRepository accountingFirmRepository, CompanyRepository companyRepository,
                          ExternalLinkRepository linkRepository, BillingChargeRepository chargeRepository,
                          TenantAccessService tenantAccessService, MessageService messageService) {
        this.registry = registry;
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
        return toSettings(currentFirm());
    }

    @Transactional
    public BillingSettingsDTO updateSettings(BillingSettingsUpdateDTO update) {
        AccountingFirm firm = currentFirm();
        if (update.provider() != BillingProviderType.MANUAL && registry.find(update.provider()).isEmpty()) {
            throw new BadRequestException(messageService.getMessage("error.billing.provider.not.connected"));
        }
        firm.setBillingProvider(update.provider());
        accountingFirmRepository.save(firm);
        if (update.provider().integrationType() != null) {
            FirmIntegration integration = firmIntegrationService.getOrCreate(firm.getId(), update.provider().integrationType());
            List<String> ids = update.feeCategoryIds() == null ? List.of() : update.feeCategoryIds();
            integration.setFeeCategoryIds(ids.isEmpty() ? null : String.join(",", ids));
            firmIntegrationService.save(integration);
        }
        return toSettings(firm);
    }

    /** Revenue categories of the chosen provider, for the "which ones are fees" picker. */
    public List<BillingCategoryDTO> listCategories() {
        AccountingFirm firm = currentFirm();
        return connectedProvider(firm).listRevenueCategories(firm).stream()
                .map(category -> new BillingCategoryDTO(category.id(), category.name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyBillingDTO getCompanyBilling(UUID companyId) {
        Company company = companyRepository.findFirstByIdAndDeletedIsFalse(companyId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.entity.not.found")));
        tenantAccessService.assertCompanyAccess(company);
        BillingProviderType provider = company.getAccountingFirm().getBillingProvider();

        var link = provider == BillingProviderType.MANUAL ? null
                : linkRepository.findFirstByCompany_IdAndProviderAndDeletedIsFalse(companyId, provider).orElse(null);
        List<BillingChargeDTO> charges = new ArrayList<>();
        BigDecimal open = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        long overdueCount = 0;
        for (BillingCharge charge : chargeRepository.findByCompany_IdAndDeletedIsFalseOrderByDueDateDesc(companyId)) {
            charges.add(toDTO(charge));
            BigDecimal remaining = charge.getOpenAmount() != null ? charge.getOpenAmount() : BigDecimal.ZERO;
            if (charge.getStatus() == ChargeStatus.OPEN || charge.getStatus() == ChargeStatus.PARTIALLY_PAID) {
                open = open.add(remaining);
            } else if (charge.getStatus() == ChargeStatus.OVERDUE) {
                overdue = overdue.add(remaining);
                overdueCount++;
            }
        }
        return new CompanyBillingDTO(provider, link == null ? null : link.getExternalId(),
                link == null ? null : link.getExternalName(), open, overdue, overdueCount, charges);
    }

    private BillingProvider connectedProvider(AccountingFirm firm) {
        if (firm.getBillingProvider() == BillingProviderType.MANUAL) {
            throw new BadRequestException(messageService.getMessage("error.billing.provider.manual"));
        }
        return registry.find(firm.getBillingProvider())
                .filter(provider -> provider.isConnected(firm))
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.billing.provider.not.connected")));
    }

    private BillingSettingsDTO toSettings(AccountingFirm firm) {
        BillingProviderType provider = firm.getBillingProvider();
        List<BillingProviderType> available = new ArrayList<>();
        available.add(BillingProviderType.MANUAL);
        available.addAll(registry.available());

        if (provider.integrationType() == null) {
            return new BillingSettingsDTO(provider, available, true, List.of(), null);
        }
        FirmIntegration integration = firmIntegrationService.find(firm.getId(), provider.integrationType()).orElse(null);
        boolean connected = registry.find(provider).map(candidate -> candidate.isConnected(firm)).orElse(false);
        return new BillingSettingsDTO(provider, available, connected,
                integration == null ? List.of() : BillingSyncService.feeCategoryIds(integration),
                integration == null ? null : integration.getLastSyncAt());
    }

    private AccountingFirm currentFirm() {
        return accountingFirmRepository.findById(firmIntegrationService.currentFirmId())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.accountingFirm.not.found")));
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
