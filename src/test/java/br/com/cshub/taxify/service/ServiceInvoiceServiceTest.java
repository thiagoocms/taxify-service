package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.xml.ServiceInvoice;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.integration.nfse.NfseXmlTest;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.SiegDownloadXmlsRequest;
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
    void eachSourceIsMarkedWhenItBringsTheNote() {
        service.store(company, bytes(NfseXmlTest.ABRASF), "SIEG");
        ArgumentCaptor<ServiceInvoice> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        ServiceInvoice invoice = saved.getValue();
        assertThat(invoice.getFoundInSiegAt()).isNotNull();
        assertThat(invoice.getFoundInAdnAt()).isNull();

        when(repository.findByCompany_IdAndAccessKey(company.getId(), invoice.getAccessKey())).thenReturn(Optional.of(invoice));
        assertThat(service.store(company, bytes(NfseXmlTest.ABRASF), "ADN")).isEqualTo(ServiceInvoiceService.Stored.DUPLICATE);
        assertThat(invoice.getFoundInAdnAt()).isNotNull();
        assertThat(invoice.getSourceSystem()).isEqualTo("SIEG"); // the origin stays the first source
    }

    @Test
    void aCancellationEventMarksTheNote() {
        ServiceInvoice invoice = new ServiceInvoice();
        when(repository.findByCompany_IdAndAccessKey(company.getId(), "K")).thenReturn(Optional.of(invoice));

        assertThat(service.markCanceled(company, "K")).isTrue();
        assertThat(invoice.isCanceled()).isTrue();
        assertThat(service.markCanceled(company, "K")).isFalse(); // already canceled
        assertThat(service.markCanceled(company, "OTHER")).isFalse();
    }

    @Test
    void somethingElseIsInvalid() {
        assertThat(service.store(company, bytes("<x/>"), "UPLOAD")).isEqualTo(ServiceInvoiceService.Stored.INVALID);
    }

    @Test
    void siegIsAskedForNfseAsProviderAndAsTakerPageByPage() {
        when(siegClient.downloadXmls(any(), eq("key"))).thenReturn(List.of(bytes(NfseXmlTest.ABRASF)));

        ServiceInvoiceService.SyncResult result = service.syncFromSieg(company.getId(), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31));

        ArgumentCaptor<SiegDownloadXmlsRequest> requests = ArgumentCaptor.captor();
        verify(siegClient, times(2)).downloadXmls(requests.capture(), eq("key"));
        assertThat(requests.getAllValues()).extracting(SiegDownloadXmlsRequest::xmlType).containsOnly(3);
        assertThat(requests.getAllValues().get(0).cnpjEmit()).isEqualTo("11222333000181");
        assertThat(requests.getAllValues().get(1).cnpjDest()).isEqualTo("11222333000181");
        assertThat(result.persistedNew()).isEqualTo(2); // the mock has no memory: both pages "new"
    }

    @org.junit.jupiter.api.Test
    void aLongPeriodGoesToSiegInWindowsOfAtMostOneMonth() {
        List<java.time.LocalDate[]> windows = ServiceInvoiceService.windows(java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 11, 23));

        org.assertj.core.api.Assertions.assertThat(windows).hasSize(4);
        org.assertj.core.api.Assertions.assertThat(windows.get(0)).containsExactly(java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31));
        org.assertj.core.api.Assertions.assertThat(windows.get(3)).containsExactly(java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 11, 23));
        org.assertj.core.api.Assertions.assertThat(ServiceInvoiceService.windows(java.time.LocalDate.of(2026, 11, 16), java.time.LocalDate.of(2026, 11, 23))).hasSize(1);
    }

    // ------------------------------------------------------------------ A1: download only with a difference

    /** A1: counts known from the NF-e sides (6 as issuer + 4 as recipient = 10), 10 already from SIEG: no request at all. */
    @Test
    void aMonthSiegAlreadyDeliveredCostsNoRequest() {
        when(repository.countByCompany_IdAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(company.getId()), any(), any())).thenReturn(10L);

        ServiceInvoiceService.SyncResult result = service.syncMonthIfMissing(company.getId(), LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 27), 6, 4);

        assertThat(result.persistedNew()).isZero();
        verify(siegClient, never()).countXmls(any(), any());
        verify(siegClient, never()).downloadXmls(any(), any());
    }

    /** A1: SIEG counts 11 and 10 came from it - the window is downloaded, both filters. */
    @Test
    void aDifferenceDownloadsTheWindow() {
        when(repository.countByCompany_IdAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(company.getId()), any(), any())).thenReturn(10L);
        when(siegClient.downloadXmls(any(), eq("key"))).thenReturn(List.of());

        service.syncMonthIfMissing(company.getId(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), 7, 4);

        ArgumentCaptor<SiegDownloadXmlsRequest> requests = ArgumentCaptor.captor();
        verify(siegClient, times(2)).downloadXmls(requests.capture(), eq("key"));
        assertThat(requests.getAllValues()).extracting(SiegDownloadXmlsRequest::cnpjEmit).containsExactly("11222333000181", null);
        assertThat(requests.getAllValues()).extracting(SiegDownloadXmlsRequest::cnpjDest).containsExactly(null, "11222333000181");
        assertThat(requests.getAllValues()).extracting(SiegDownloadXmlsRequest::skip).containsOnly(0);
    }

    /** A1: the previous month has no count from this attempt - one contar-xmls per filter, NFS-e field, same window. */
    @Test
    void unknownCountsAreAskedOncePerFilter() {
        when(siegClient.countXmls(any(), eq("key"))).thenReturn(new br.com.cshub.taxify.integration.sieg.SiegDocumentCounts(99, 2));
        when(repository.countByCompany_IdAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(company.getId()),
                eq(LocalDate.of(2026, 9, 30).atTime(21, 0)), eq(LocalDate.of(2026, 10, 31).atTime(java.time.LocalTime.MAX).minusHours(3)))).thenReturn(4L); // SIEG's UTC days in Recife time

        service.syncMonthIfMissing(company.getId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null);

        ArgumentCaptor<SiegDownloadXmlsRequest> counts = ArgumentCaptor.captor();
        verify(siegClient, times(2)).countXmls(counts.capture(), eq("key"));
        assertThat(counts.getAllValues()).extracting(SiegDownloadXmlsRequest::cnpjEmit).containsExactly("11222333000181", null);
        assertThat(counts.getAllValues()).extracting(SiegDownloadXmlsRequest::cnpjDest).containsExactly(null, "11222333000181");
        // 2 + 2 = 4 delivered: the NF-e count (99) is not looked at
        verify(siegClient, never()).downloadXmls(any(), any());
    }

    /** A1: a count that fails is "unknown" - the window is downloaded, as before the gate. */
    @Test
    void aFailedCountDownloadsTheWindow() {
        when(siegClient.countXmls(any(), eq("key"))).thenThrow(new br.com.cshub.taxify.exception.SiegIntegrationException(
                "error.sieg.integration.failed", "x", 503));
        when(repository.countByCompany_IdAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(company.getId()), any(), any())).thenReturn(50L);
        when(siegClient.downloadXmls(any(), eq("key"))).thenReturn(List.of());

        service.syncMonthIfMissing(company.getId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, 3);

        verify(siegClient, times(1)).countXmls(any(), any());
        verify(siegClient, times(2)).downloadXmls(any(), any());
    }
}
