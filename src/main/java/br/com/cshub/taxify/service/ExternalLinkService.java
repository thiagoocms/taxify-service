package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.ExternalLink;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.billing.ExternalLinkDTO;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.billing.BillingProvider;
import br.com.cshub.taxify.integration.billing.BillingProviderRegistry;
import br.com.cshub.taxify.integration.billing.ExternalCustomer;
import br.com.cshub.taxify.repository.ExternalLinkRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Which customer of the firm's billing provider a company is. The sync links by CNPJ; when
 * that fails (customer registered under the owner's CPF, another branch...) the link is set
 * here by hand or looked up by another document.
 */
@Service
public class ExternalLinkService {

    private final ExternalLinkRepository repository;
    private final CompanyValidation companyValidation;
    private final BillingProviderRegistry registry;
    private final MessageService messageService;

    public ExternalLinkService(ExternalLinkRepository repository, CompanyValidation companyValidation,
                               BillingProviderRegistry registry, MessageService messageService) {
        this.repository = repository;
        this.companyValidation = companyValidation;
        this.registry = registry;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public Optional<ExternalLinkDTO> find(UUID companyId) {
        Company company = companyValidation.checkExist(companyId);
        BillingProviderType provider = company.getAccountingFirm().getBillingProvider();
        if (provider == null || provider == BillingProviderType.MANUAL) {
            return Optional.empty();
        }
        return repository.findFirstByCompany_IdAndProviderAndDeletedIsFalse(companyId, provider).map(ExternalLinkService::toDto);
    }

    @Transactional
    public ExternalLinkDTO save(UUID companyId, ExternalLinkDTO link) {
        Company company = companyValidation.checkExist(companyId);
        return toDto(upsert(company, providerOf(company.getAccountingFirm()), link.externalId().strip(), link.externalName()));
    }

    /** Finds the provider's customer by this CNPJ/CPF and links it. */
    @Transactional
    public ExternalLinkDTO lookup(UUID companyId, String document) {
        Company company = companyValidation.checkExist(companyId);
        AccountingFirm firm = company.getAccountingFirm();
        BillingProvider provider = registry.requireConnected(firm);
        ExternalCustomer customer = provider.findCustomerByDocument(firm, DocumentNumbers.onlyDigits(document))
                .orElseThrow(() -> new BadRequestException("error.billing.link.customer.not.found", messageService.getMessage("error.billing.link.customer.not.found")));
        return toDto(upsert(company, provider.type(), customer.id(), customer.name()));
    }

    @Transactional
    public void delete(UUID companyId) {
        Company company = companyValidation.checkExist(companyId);
        repository.findFirstByCompany_IdAndProviderAndDeletedIsFalse(companyId, providerOf(company.getAccountingFirm()))
                .ifPresent(link -> {
                    link.setDeleted(true);
                    repository.save(link);
                });
    }

    private BillingProviderType providerOf(AccountingFirm firm) {
        BillingProviderType provider = firm.getBillingProvider();
        if (provider == null || provider == BillingProviderType.MANUAL) {
            throw new BadRequestException("error.billing.provider.manual", messageService.getMessage("error.billing.provider.manual"));
        }
        return provider;
    }

    private ExternalLink upsert(Company company, BillingProviderType provider, String externalId, String externalName) {
        ExternalLink link = repository.findFirstByCompany_IdAndProviderAndDeletedIsFalse(company.getId(), provider)
                .orElseGet(() -> new ExternalLink(company, provider, externalId, externalName));
        link.setExternalId(externalId);
        link.setExternalName(externalName == null || externalName.isBlank() ? null : externalName.strip());
        link.setDeleted(false);
        return repository.save(link);
    }

    private static ExternalLinkDTO toDto(ExternalLink link) {
        return new ExternalLinkDTO(link.getProvider(), link.getExternalId(), link.getExternalName());
    }
}
