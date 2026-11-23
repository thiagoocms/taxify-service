package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.xml.ServiceInvoice;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.integration.nfse.NfseXmlTest;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.SiegBaixarXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.repository.ServiceInvoiceRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceInvoiceServiceTest {

    @Mock
    private ServiceInvoiceRepository repository;
    @Mock
    private FiscalXmlFileStorage fileStorage;
    @Mock
    private SiegClient siegClient;
    @Mock
    private FirmIntegrationService firmIntegrationService;
    @Mock
    private CompanyValidation companyValidation;

    private ServiceInvoiceService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new ServiceInvoiceService(repository, fileStorage, siegClient, firmIntegrationService, companyValidation);
        company = new Company(UUID.randomUUID());
        company.setDocument(new Document("11222333000181", DocumentTypeEnum.CNPJ));
        AccountingFirm firm = new AccountingFirm();
        firm.setId(UUID.randomUUID());
        company.setAccountingFirm(firm);
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        when(repository.findByCompany_IdAndAccessKey(any(), anyString())).thenReturn(Optional.empty());
        when(fileStorage.store(any(), anyString(), any())).thenReturn("path.xml");
        when(firmIntegrationService.getApiKey(firm.getId(), IntegrationType.SIEG)).thenReturn("key");
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theDirectionComesFromTheCompanysCnpj() {
        service.store(company, bytes(NfseXmlTest.national("1", "2")), "UPLOAD");
        service.store(company, bytes(NfseXmlTest.ABRASF), "UPLOAD");

        ArgumentCaptor<ServiceInvoice> saved = ArgumentCaptor.captor();
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getDirection()).isEqualTo("PRESTADA"); // company is the provider
        assertThat(saved.getAllValues().get(1).getDirection()).isEqualTo("TOMADA");   // company is the taker
        assertThat(saved.getAllValues().get(1).getSourceSystem()).isEqualTo("UPLOAD");
        assertThat(saved.getAllValues().get(1).getXmlFilePath()).isEqualTo("path.xml");
    }

    @Test
    void aKnownNoteIsSkippedButTakesACancellation() {
        ServiceInvoice known = new ServiceInvoice();
        when(repository.findByCompany_IdAndAccessKey(company.getId(), "ABRASF-2611606-55444333000122-4567")).thenReturn(Optional.of(known));

        assertThat(service.store(company, bytes(NfseXmlTest.ABRASF), "SIEG")).isEqualTo(ServiceInvoiceService.Stored.DUPLICATE);
        assertThat(known.isCanceled()).isFalse();

        String canceled = NfseXmlTest.ABRASF.replace("</Nfse>", "</Nfse><NfseCancelamento/>");
        service.store(company, bytes(canceled), "SIEG");
        assertThat(known.isCanceled()).isTrue();
        verify(fileStorage, never()).store(any(), anyString(), any());
    }

    @Test
    void somethingElseIsInvalid() {
        assertThat(service.store(company, bytes("<x/>"), "UPLOAD")).isEqualTo(ServiceInvoiceService.Stored.INVALID);
    }

    @Test
    void siegIsAskedForNfseAsProviderAndAsTakerPageByPage() {
        when(siegClient.baixarXmls(any(), eq("key"))).thenReturn(List.of(bytes(NfseXmlTest.ABRASF)));

        ServiceInvoiceService.SyncResult result = service.syncFromSieg(company.getId(), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31));

        ArgumentCaptor<SiegBaixarXmlsRequest> requests = ArgumentCaptor.captor();
        verify(siegClient, times(2)).baixarXmls(requests.capture(), eq("key"));
        assertThat(requests.getAllValues()).extracting(SiegBaixarXmlsRequest::tipoXml).containsOnly(3);
        assertThat(requests.getAllValues().get(0).cnpjEmit()).isEqualTo("11222333000181");
        assertThat(requests.getAllValues().get(1).cnpjDest()).isEqualTo("11222333000181");
        assertThat(result.persistedNew()).isEqualTo(2); // the mock has no memory: both pages "new"
    }
}
