package br.com.cshub.taxify.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalLinkServiceTest {

    private final ExternalLinkRepository repository = mock(ExternalLinkRepository.class);
    private final CompanyValidation companyValidation = mock(CompanyValidation.class);
    private final BillingProviderRegistry registry = mock(BillingProviderRegistry.class);
    private final MessageService messageService = mock(MessageService.class);
    private final ExternalLinkService service = new ExternalLinkService(repository, companyValidation, registry, messageService);

    private final AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
    private final Company company = new Company(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        firm.setBillingProvider(BillingProviderType.CONTA_AZUL);
        company.setAccountingFirm(firm);
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        when(repository.save(any(ExternalLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageService.getMessage(anyString())).thenReturn("msg");
    }

    @Test
    void manualLinkReplacesTheOneTheSyncMade() {
        ExternalLink synced = new ExternalLink(company, BillingProviderType.CONTA_AZUL, "wrong", "Outra empresa");
        when(repository.findFirstByCompany_IdAndProviderAndDeletedIsFalse(company.getId(), BillingProviderType.CONTA_AZUL))
                .thenReturn(Optional.of(synced));

        ExternalLinkDTO saved = service.save(company.getId(), new ExternalLinkDTO(null, " right ", "Acme"));

        assertThat(saved.externalId()).isEqualTo("right");
        assertThat(synced.getExternalName()).isEqualTo("Acme");
    }

    @Test
    void lookupLinksTheCustomerFoundByAnotherDocument() {
        BillingProvider provider = mock(BillingProvider.class);
        when(provider.type()).thenReturn(BillingProviderType.CONTA_AZUL);
        when(registry.requireConnected(firm)).thenReturn(provider);
        when(provider.findCustomerByDocument(firm, "12345678901")).thenReturn(Optional.of(new ExternalCustomer("c-1", "João", "12345678901")));
        when(repository.findFirstByCompany_IdAndProviderAndDeletedIsFalse(company.getId(), BillingProviderType.CONTA_AZUL))
                .thenReturn(Optional.empty());

        ExternalLinkDTO saved = service.lookup(company.getId(), "123.456.789-01");

        assertThat(saved.externalId()).isEqualTo("c-1");
        assertThat(saved.externalName()).isEqualTo("João");
    }

    @Test
    void aManualFirmHasNoProviderToLinkTo() {
        firm.setBillingProvider(BillingProviderType.MANUAL);

        assertThat(service.find(company.getId())).isEmpty();
        assertThatThrownBy(() -> service.save(company.getId(), new ExternalLinkDTO(null, "x", null))).isInstanceOf(BadRequestException.class);
    }
}
