package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.pendency.CaptureRun;
import br.com.cshub.taxify.domain.xml.NfseAdnControl;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.nfse.AdnClient;
import br.com.cshub.taxify.integration.nfse.AdnProperties;
import br.com.cshub.taxify.integration.nfse.AdnResponse;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.NfseAdnControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NfseAdnServiceTest {

    private static final String CNPJ = "11222333000181";
    private static final String KEY = "26116062112233300018100000000000012345678901234567";

    @Mock
    private NfseAdnControlRepository controlRepository;
    @Mock
    private CompanyCertificateService certificateService;
    @Mock
    private ServiceInvoiceService serviceInvoiceService;
    @Mock
    private AdnClient client;
    @Mock
    private CaptureRunRepository captureRunRepository;

    private NfseAdnService service;
    private Company company;
    private final A1Certificate certificate = mock(A1Certificate.class);

    @BeforeEach
    void setUp() throws Exception {
        AdnProperties properties = new AdnProperties();
        properties.setPauseBetweenBatchesMillis(0);
        service = new NfseAdnService(controlRepository, certificateService, serviceInvoiceService, client, properties,
                captureRunRepository);
        company = new Company(UUID.randomUUID());
        company.setDocument(new Document(CNPJ, DocumentTypeEnum.CNPJ));
        CompanyCertificate stored = new CompanyCertificate();
        stored.setValidUntil(LocalDateTime.now().plusYears(1));
        when(certificateService.resolve(company)).thenReturn(Optional.of(stored));
        when(certificateService.open(stored)).thenReturn(certificate);
        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.empty());
        when(controlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceInvoiceService.store(any(), any(), anyString())).thenReturn(ServiceInvoiceService.Stored.NEW);
    }

    private static AdnResponse.Document note(long nsu) {
        return new AdnResponse.Document(nsu, KEY, "NFSE", null, "<NFSe/>".getBytes(StandardCharsets.UTF_8));
    }

    private static AdnResponse batch(List<AdnResponse.Document> documents) {
        return new AdnResponse(200, AdnResponse.FOUND, documents, List.of());
    }

    @Test
    void readsUntilCaughtUpStoringNotesAndCancellations() throws Exception {
        List<AdnResponse.Document> full = new ArrayList<>();
        for (long nsu = 1; nsu <= 50; nsu++) {
            full.add(note(nsu));
        }
        AdnResponse.Document cancellation = new AdnResponse.Document(52, null, "EVENTO", null,
                ("<evento><infPedReg><chNFSe>" + KEY + "</chNFSe><e101101/></infPedReg></evento>").getBytes(StandardCharsets.UTF_8));
        when(client.fetch(certificate, CNPJ, 0L)).thenReturn(batch(full));
        when(client.fetch(certificate, CNPJ, 50L)).thenReturn(batch(List.of(note(51), cancellation)));
        when(serviceInvoiceService.markCanceled(company, KEY)).thenReturn(true);

        NfseAdnControl control = service.capture(company);

        verify(serviceInvoiceService, times(51)).store(eq(company), any(), eq("ADN"));
        verify(serviceInvoiceService).markCanceled(company, KEY);
        assertThat(control.getUltNsu()).isEqualTo(52);
        assertThat(control.getLastStatus()).isEqualTo(NfseAdnControl.Status.OK);
        assertThat(control.getLastNewNotes()).isEqualTo(51);
        assertThat(control.getLastDetail()).contains("1 NFS-e cancelada");
        assertThat(control.getNextQueryAt()).isAfter(LocalDateTime.now().plusMinutes(50));
        ArgumentCaptor<CaptureRun> run = ArgumentCaptor.captor();
        verify(captureRunRepository).save(run.capture());
        assertThat(run.getValue().getService()).isEqualTo("NFSE_ADN");
    }

    @Test
    void nothingNewWaitsWithoutError() throws Exception {
        when(client.fetch(any(), anyString(), anyLong())).thenReturn(new AdnResponse(404, AdnResponse.NONE, List.of(), List.of()));

        NfseAdnControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(NfseAdnControl.Status.SEM_MOVIMENTO);
        assertThat(control.getNextQueryAt()).isAfter(LocalDateTime.now().plusMinutes(50));
        verify(serviceInvoiceService, never()).store(any(), any(), anyString());
    }

    @Test
    void aRejectionIsAnErrorAndKeepsTheNsu() throws Exception {
        when(client.fetch(any(), anyString(), anyLong())).thenReturn(new AdnResponse(400, AdnResponse.REJECTED, List.of(),
                List.of("E1235 Certificado não pertence ao CNPJ consultado")));

        NfseAdnControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(NfseAdnControl.Status.ERRO);
        assertThat(control.getLastDetail()).isEqualTo("E1235 Certificado não pertence ao CNPJ consultado");
        assertThat(control.getUltNsu()).isZero();
        assertThat(control.getNextQueryAt()).isNull();
    }

    @Test
    void throttlingWaitsAnHour() throws Exception {
        when(client.fetch(any(), anyString(), anyLong())).thenReturn(new AdnResponse(429, null, List.of(), List.of()));

        NfseAdnControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(NfseAdnControl.Status.SEM_MOVIMENTO);
        assertThat(control.getLastDetail()).contains("429");
        assertThat(control.getNextQueryAt()).isAfter(LocalDateTime.now().plusMinutes(50));
    }

    @Test
    void doesNotAskBeforeTheWaitIsOverNorWithoutCertificate() {
        NfseAdnControl waiting = new NfseAdnControl();
        waiting.setNextQueryAt(LocalDateTime.now().plusMinutes(10));
        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.of(waiting));
        service.capture(company);
        verifyNoInteractions(client);

        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.empty());
        when(certificateService.resolve(company)).thenReturn(Optional.empty());
        NfseAdnControl control = service.capture(company);
        assertThat(control.getLastDetail()).isEqualTo("Sem certificado A1.");
        verifyNoInteractions(client);
    }

    @Test
    void cancellationIsRecognizedByTypeOrXml() {
        assertThat(NfseAdnService.isCancellation(new AdnResponse.Document(1, KEY, "EVENTO", "101101", new byte[0]))).isTrue();
        assertThat(NfseAdnService.isCancellation(new AdnResponse.Document(1, KEY, "EVENTO", null,
                "<e105102/>".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(NfseAdnService.isCancellation(new AdnResponse.Document(1, KEY, "EVENTO", null,
                "<e202201/>".getBytes(StandardCharsets.UTF_8)))).isFalse();
    }
}
