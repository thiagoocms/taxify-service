package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureStage;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.nfse.AdnClient;
import br.com.cshub.taxify.integration.nfse.AdnProperties;
import br.com.cshub.taxify.integration.nfse.AdnResponse;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.SefazDistClient;
import br.com.cshub.taxify.integration.sefaz.SefazDistResponse;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.NfseAdnControlRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.service.capture.CaptureStageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-CP-3 (CP-RN-10, CP-RN-11, CT-CP-24 a 26): SEFAZ and ADN record the safe code of each run - the cStat or the HTTP
 * status, never xMotivo or the provider's text; last_detail stays as it was.
 */
class DirectCaptureStageCodesTest {

    private static final String CNPJ = "11222333000181";

    private final CaptureStageRecorder recorder = mock(CaptureStageRecorder.class);
    private final CompanyCertificateService certificates = mock(CompanyCertificateService.class);
    private final A1Certificate certificate = mock(A1Certificate.class);
    private Company company;
    private CompanyCertificate stored;

    @BeforeEach
    void setUp() throws Exception {
        company = new Company(UUID.randomUUID());
        company.setDocument(new Document(CNPJ, DocumentTypeEnum.CNPJ));
        company.setUf("PE");
        stored = new CompanyCertificate();
        stored.setValidUntil(LocalDateTime.now().plusYears(1));
        when(certificates.resolve(company)).thenReturn(Optional.of(stored));
        when(certificates.open(stored)).thenReturn(certificate);
    }

    private SefazDistService sefaz(SefazDistClient client) {
        SefazDistControlRepository controls = mock(SefazDistControlRepository.class);
        when(controls.findByCompany_Id(company.getId())).thenReturn(Optional.empty());
        when(controls.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SefazManifestService manifest = mock(SefazManifestService.class);
        when(manifest.manifestAndFetch(eq(company), any(), anyString(), anyBoolean())).thenReturn(SefazManifestService.Outcome.NONE);
        SefazProperties properties = new SefazProperties();
        properties.setPauseBetweenBatchesMillis(0);
        SefazDistService service = new SefazDistService(controls, certificates, mock(FiscalXmlIntegrationService.class), client,
                properties, manifest, mock(CaptureRunRepository.class));
        service.setStageRecorder(recorder);
        return service;
    }

    /** CT-CP-24: 656 = SUCCESS with SEFAZ_CONSUMO_INDEVIDO. */
    @Test
    void consumoIndevidoIsAWarning() throws Exception {
        SefazDistClient client = mock(SefazDistClient.class);
        when(client.fetch(any(), anyString(), anyString(), anyString()))
                .thenReturn(new SefazDistResponse("656", "Consumo Indevido 12345678000190", null, null, List.of()));

        sefaz(client).capture(company);

        verify(recorder).success(company.getId(), CaptureStage.SEFAZ_DIST, CaptureFailureCode.SEFAZ_CONSUMO_INDEVIDO, null);
    }

    /** CT-CP-25: cStat 593 = SEFAZ_REJECTED 593; xMotivo never goes to the stage. */
    @Test
    void rejectionRecordsTheCstat() throws Exception {
        SefazDistClient client = mock(SefazDistClient.class);
        when(client.fetch(any(), anyString(), anyString(), anyString()))
                .thenReturn(new SefazDistResponse("593", "CNPJ 12345678000190 divergente", null, null, List.of()));

        sefaz(client).capture(company);

        verify(recorder).failure(company.getId(), CaptureStage.SEFAZ_DIST, CaptureFailureCode.SEFAZ_REJECTED, "593");
    }

    @Test
    void sefazTimeoutAndPreconditions() throws Exception {
        SefazDistClient client = mock(SefazDistClient.class);
        when(client.fetch(any(), anyString(), anyString(), anyString())).thenThrow(new SocketTimeoutException("Read timed out"));
        sefaz(client).capture(company);
        verify(recorder).failure(company.getId(), CaptureStage.SEFAZ_DIST, CaptureFailureCode.SEFAZ_TIMEOUT, null);

        when(certificates.resolve(company)).thenReturn(Optional.empty());
        sefaz(mock(SefazDistClient.class)).capture(company);
        verify(recorder).failure(company.getId(), CaptureStage.SEFAZ_DIST, CaptureFailureCode.CERT_MISSING, null);

        stored.setValidUntil(LocalDateTime.now().minusDays(7));
        when(certificates.resolve(company)).thenReturn(Optional.of(stored));
        sefaz(mock(SefazDistClient.class)).capture(company);
        verify(recorder).failure(company.getId(), CaptureStage.SEFAZ_DIST, CaptureFailureCode.CERT_EXPIRED, null);

        company.setUf(null);
        sefaz(mock(SefazDistClient.class)).capture(company);
        verify(recorder).failure(company.getId(), CaptureStage.SEFAZ_DIST, CaptureFailureCode.COMPANY_WITHOUT_UF, null);
    }

    private NfseAdnService adn(AdnClient client) {
        NfseAdnControlRepository controls = mock(NfseAdnControlRepository.class);
        when(controls.findByCompany_Id(company.getId())).thenReturn(Optional.empty());
        when(controls.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AdnProperties properties = new AdnProperties();
        properties.setPauseBetweenBatchesMillis(0);
        NfseAdnService service = new NfseAdnService(controls, certificates, mock(ServiceInvoiceService.class), client, properties,
                mock(CaptureRunRepository.class));
        service.setStageRecorder(recorder);
        return service;
    }

    @Test
    void adnRejectionTimeoutAndSuccess() throws Exception {
        AdnClient rejecting = mock(AdnClient.class);
        when(rejecting.fetch(any(), anyString(), anyLong()))
                .thenReturn(new AdnResponse(400, AdnResponse.REJECTED, List.of(), List.of("CNPJ 12345678000190 invalido")));
        adn(rejecting).capture(company);
        verify(recorder).failure(company.getId(), CaptureStage.NFSE_ADN, CaptureFailureCode.ADN_REJECTED, "400");

        AdnClient slow = mock(AdnClient.class);
        when(slow.fetch(any(), anyString(), anyLong())).thenThrow(new SocketTimeoutException());
        adn(slow).capture(company);
        verify(recorder).failure(company.getId(), CaptureStage.NFSE_ADN, CaptureFailureCode.ADN_TIMEOUT, null);

        AdnClient nothing = mock(AdnClient.class);
        when(nothing.fetch(any(), anyString(), anyLong())).thenReturn(new AdnResponse(404, AdnResponse.NONE, List.of(), List.of()));
        adn(nothing).capture(company);
        verify(recorder).success(company.getId(), CaptureStage.NFSE_ADN);
    }

    /** Waiting time not over: the run does not happen and nothing is recorded (a skipped stage). */
    @Test
    void aSkippedRunRecordsNothing() {
        SefazDistControlRepository controls = mock(SefazDistControlRepository.class);
        br.com.cshub.taxify.domain.xml.SefazDistControl waiting = new br.com.cshub.taxify.domain.xml.SefazDistControl();
        waiting.setNextQueryAt(LocalDateTime.now().plusMinutes(30));
        when(controls.findByCompany_Id(company.getId())).thenReturn(Optional.of(waiting));
        SefazDistService service = new SefazDistService(controls, certificates, mock(FiscalXmlIntegrationService.class),
                mock(SefazDistClient.class), new SefazProperties(), mock(SefazManifestService.class), mock(CaptureRunRepository.class));
        service.setStageRecorder(recorder);

        service.capture(company);

        verify(recorder, never()).success(any(), any(), any(), any());
        verify(recorder, never()).failure(any(), any(), any(CaptureFailureCode.class), any());
    }
}
