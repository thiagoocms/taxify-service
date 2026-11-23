package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.SefazDistClient;
import br.com.cshub.taxify.integration.sefaz.SefazDistResponse;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SefazDistServiceTest {

    private static final String CNPJ = "11222333000181";

    @Mock
    private SefazDistControlRepository controlRepository;
    @Mock
    private CompanyCertificateService certificateService;
    @Mock
    private FiscalXmlIntegrationService integrationService;
    @Mock
    private SefazDistClient client;

    private SefazDistService service;
    private Company company;
    private A1Certificate certificate;

    @BeforeEach
    void setUp() throws Exception {
        SefazProperties properties = new SefazProperties();
        properties.setPauseBetweenBatchesMillis(0);
        properties.setMaxBatchesPerCompany(5);
        service = new SefazDistService(controlRepository, certificateService, integrationService, client, properties);

        company = new Company(UUID.randomUUID());
        company.setDocument(new Document(CNPJ, DocumentTypeEnum.CNPJ));
        company.setUf("PE");

        CompanyCertificate stored = new CompanyCertificate();
        stored.setValidUntil(LocalDateTime.now().plusYears(1));
        certificate = mock(A1Certificate.class);
        when(certificateService.resolve(company)).thenReturn(Optional.of(stored));
        when(certificateService.open(stored)).thenReturn(certificate);
        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.empty());
        when(controlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(integrationService.importFromSefaz(eq(company.getId()), any()))
                .thenAnswer(invocation -> result(((List<?>) invocation.getArgument(1)).size()));
    }

    private static XmlDownloadResultDTO result(int persisted) {
        return new XmlDownloadResultDTO(persisted, persisted, persisted, 0, 0, List.of(), List.of(), 0);
    }

    private static SefazDistResponse.Document doc(String nsu, String schema) {
        return new SefazDistResponse.Document(nsu, schema, ("<x nsu=\"" + nsu + "\"/>").getBytes());
    }

    @Test
    void readsBatchesUntilCaughtUpStoringOnlyFullDocuments() throws Exception {
        when(client.fetch(certificate, "PE", CNPJ, "000000000000001")).thenReturn(new SefazDistResponse("138", "ok",
                "000000000000003", "000000000000005",
                List.of(doc("1", "procNFe_v4.00.xsd"), doc("2", "resNFe_v1.01.xsd"), doc("3", "procEventoNFe_v1.00.xsd"))));
        when(client.fetch(certificate, "PE", CNPJ, "000000000000003")).thenReturn(new SefazDistResponse("138", "ok",
                "000000000000005", "000000000000005", List.of(doc("5", "resEvento_v1.01.xsd"))));

        SefazDistControl control = service.capture(company);

        ArgumentCaptor<List<byte[]>> stored = ArgumentCaptor.captor();
        verify(integrationService).importFromSefaz(eq(company.getId()), stored.capture());
        assertThat(stored.getValue()).hasSize(2); // procNFe + procEventoNFe; resNFe and resEvento are not stored
        assertThat(control.getUltNsu()).isEqualTo("000000000000005");
        assertThat(control.getNextQueryAt()).isAfter(LocalDateTime.now().plusMinutes(60));
        assertThat(control.getLastStatus()).isEqualTo(SefazDistControl.Status.OK);
        assertThat(control.getLastNewNotes()).isEqualTo(2);
        assertThat(control.getLastSummaries()).isEqualTo(1);
        assertThat(control.getLastDetail()).contains("Ciência da Operação");
    }

    @Test
    void consumoIndevidoWaitsAnHourWithoutError() throws Exception {
        when(client.fetch(any(), anyString(), anyString(), anyString()))
                .thenReturn(new SefazDistResponse("656", "Consumo Indevido", null, null, List.of()));

        SefazDistControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(SefazDistControl.Status.SEM_MOVIMENTO);
        assertThat(control.getNextQueryAt()).isAfter(LocalDateTime.now().plusMinutes(60));
        assertThat(control.getUltNsu()).isEqualTo("000000000000001");
    }

    @Test
    void consumoIndevidoWithTheNsuSefazExpectsContinuesFromItAfterThePause() throws Exception {
        when(client.fetch(any(), anyString(), anyString(), anyString())).thenReturn(new SefazDistResponse("656",
                "Rejeicao: Consumo Indevido (Deve ser utilizado o ultNSU nas solicitacoes subsequentes. Tente apos 1 hora)",
                "000000000004321", "000000000004400", List.of()));

        SefazDistControl control = service.capture(company);

        assertThat(control.getUltNsu()).isEqualTo("000000000004321");
        assertThat(control.getMaxNsu()).isEqualTo("000000000004400");
        assertThat(control.getNextQueryAt()).isAfter(LocalDateTime.now().plusMinutes(60));
        assertThat(control.getLastStatus()).isEqualTo(SefazDistControl.Status.SEM_MOVIMENTO);
        assertThat(control.getLastDetail()).contains("NSU ajustado de 000000000000001 para 000000000004321")
                .contains("Consumo Indevido");
    }

    @Test
    void consumoIndevidoNeverMovesTheNsuBack() throws Exception {
        SefazDistControl ahead = new SefazDistControl();
        ahead.setUltNsu("000000000005000");
        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.of(ahead));
        when(client.fetch(any(), anyString(), anyString(), anyString()))
                .thenReturn(new SefazDistResponse("656", "Consumo Indevido", "000000000004321", null, List.of()));

        SefazDistControl control = service.capture(company);

        assertThat(control.getUltNsu()).isEqualTo("000000000005000");
    }

    @Test
    void nsuComparison() {
        assertThat(SefazDistService.isAhead("000000000000010", "000000000000009")).isTrue();
        assertThat(SefazDistService.isAhead("000000000000009", "000000000000010")).isFalse();
        assertThat(SefazDistService.isAhead(null, "0")).isFalse();
        assertThat(SefazDistService.isAhead("", "0")).isFalse();
        assertThat(SefazDistService.isAhead("12", null)).isTrue();
    }

    @Test
    void doesNotAskBeforeTheWaitingHourIsOver() {
        SefazDistControl waiting = new SefazDistControl();
        waiting.setNextQueryAt(LocalDateTime.now().plusMinutes(30));
        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.of(waiting));

        service.capture(company);

        verifyNoInteractions(client);
        verify(controlRepository, never()).save(any());
    }

    @Test
    void rejectionKeepsTheNsuAndRecordsTheReason() throws Exception {
        when(client.fetch(any(), anyString(), anyString(), anyString()))
                .thenReturn(new SefazDistResponse("641", "NF-e indisponivel para o emitente", null, null, List.of()));

        SefazDistControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(SefazDistControl.Status.ERRO);
        assertThat(control.getLastDetail()).isEqualTo("641 NF-e indisponivel para o emitente");
        assertThat(control.getNextQueryAt()).isNull();
    }

    @Test
    void connectionFailureIsAnErrorForThisRunOnly() throws Exception {
        when(client.fetch(any(), anyString(), anyString(), anyString())).thenThrow(new IOException("timeout"));

        SefazDistControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(SefazDistControl.Status.ERRO);
        assertThat(control.getLastDetail()).contains("timeout");
        assertThat(control.getNextQueryAt()).isNull();
    }

    @Test
    void missingCertificateFileAsksToUploadAgainInsteadOfBlamingSefaz() throws Exception {
        CompanyCertificate stored = new CompanyCertificate();
        stored.setValidUntil(LocalDateTime.now().plusYears(1));
        when(certificateService.resolve(company)).thenReturn(Optional.of(stored));
        when(certificateService.open(stored)).thenThrow(new java.nio.file.NoSuchFileException(".\\data\\fiscal-xml\\certificados\\a.pfx"));

        SefazDistControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(SefazDistControl.Status.ERRO);
        assertThat(control.getLastDetail()).isEqualTo("Arquivo do certificado não encontrado no armazenamento - envie o certificado novamente.");
        verifyNoInteractions(client);
    }

    @Test
    void expiredCertificateIsNotUsed() throws Exception {
        CompanyCertificate expired = new CompanyCertificate();
        expired.setValidUntil(LocalDateTime.of(2026, 1, 10, 0, 0));
        when(certificateService.resolve(company)).thenReturn(Optional.of(expired));

        SefazDistControl control = service.capture(company);

        assertThat(control.getLastStatus()).isEqualTo(SefazDistControl.Status.ERRO);
        assertThat(control.getLastDetail()).isEqualTo("Certificado vencido em 10/01/2026.");
        verifyNoInteractions(client);
    }
}
