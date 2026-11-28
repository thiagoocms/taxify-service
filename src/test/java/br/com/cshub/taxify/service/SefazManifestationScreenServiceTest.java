package br.com.cshub.taxify.service;

import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentEvent;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus;
import br.com.cshub.taxify.dto.fiscalxml.ManifestationDTOs;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient.EventRequest;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient.Manifestation;
import br.com.cshub.taxify.integration.sefaz.SefazEventResponse;
import br.com.cshub.taxify.domain.fiscaldocument.Nfe;
import br.com.cshub.taxify.repository.NfeRepository;
import br.com.cshub.taxify.repository.FiscalDocumentEventRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class SefazManifestationScreenServiceTest {

    private static final String CNPJ = "11222333000181";
    private static final String KEY_1 = "26241199888777000166550010000001231000000010";
    private static final String KEY_2 = "26241199888777000166550020000004561000000025";

    @Mock
    private NfeRepository documentRepository;
    @Mock
    private SefazNoteSummaryRepository summaryRepository;
    @Mock
    private FiscalDocumentEventRepository eventRepository;
    @Mock
    private FiscalXmlEventService eventService;
    @Mock
    private CompanyCertificateService certificateService;
    @Mock
    private SefazManifestService manifestService;
    @Mock
    private SefazEventClient eventClient;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private MessageService messageService;

    private SefazManifestationScreenService service;
    private Company company;
    private final A1Certificate certificate = mock(A1Certificate.class);

    @BeforeEach
    void setUp() throws Exception {
        service = new SefazManifestationScreenService(documentRepository, summaryRepository, eventRepository, eventService,
                certificateService, manifestService, eventClient, companyValidation, messageService);
        company = new Company(UUID.randomUUID());
        company.setDocument(new Document(CNPJ, DocumentTypeEnum.CNPJ));
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        when(messageService.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(manifestService.manifests(company.getId())).thenReturn(true);
        CompanyCertificate stored = new CompanyCertificate();
        stored.setValidUntil(LocalDateTime.now().plusYears(1));
        when(certificateService.resolve(company)).thenReturn(Optional.of(stored));
        when(certificateService.open(stored)).thenReturn(certificate);
        when(documentRepository.findAwaitingManifestation(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(summaryRepository.findAwaitingManifestation(any(), any(), any(), any())).thenReturn(List.of());
        when(summaryRepository.findByCompany_IdAndAccessKey(any(), anyString())).thenReturn(Optional.empty());
    }

    private ManifestationDTOs.Request request(String type, String justification, String... keys) {
        return new ManifestationDTOs.Request(company.getId().toString(), type, justification, List.of(keys));
    }

    @Test
    void listsStoredNotesAndSummariesByDeadlineWithTheirCiencia() {
        Nfe stored = (Nfe) TestNotes.note(company).key(KEY_1).number("123").issuer("99888777000166", "FORNECEDOR A")
                .issuedAt(LocalDate.now().minusDays(100).atStartOfDay()).total("50.00").build();
        SefazNoteSummary summary = new SefazNoteSummary();
        summary.setAccessKey(KEY_2);
        summary.setIssuerName("FORNECEDOR B");
        summary.setIssueDate(LocalDate.now().minusDays(170).atStartOfDay());
        when(documentRepository.findAwaitingManifestation(eq(company.getId()), eq(CNPJ), any(), any(), any())).thenReturn(List.of(stored));
        when(summaryRepository.findAwaitingManifestation(eq(company.getId()), any(), any(), any())).thenReturn(List.of(summary));
        FiscalDocumentEvent ciencia = new FiscalDocumentEvent(company, KEY_1, "210210", 1, null, null, null, null, true);
        when(eventRepository.findByCompany_IdAndAccessKeyInAndRegisteredIsTrue(eq(company.getId()), any())).thenReturn(List.of(ciencia));

        ManifestationDTOs.Pending pending = service.pending(company.getId().toString());

        assertThat(pending.habilitada()).isTrue();
        assertThat(pending.certificado()).isTrue();
        assertThat(pending.notas()).extracting(ManifestationDTOs.Note::chave).containsExactly(KEY_2, KEY_1); // nearest deadline first
        ManifestationDTOs.Note onlySummary = pending.notas().get(0);
        assertThat(onlySummary.xmlCompleto()).isFalse();
        assertThat(onlySummary.notaId()).isNull();
        assertThat(onlySummary.numero()).isEqualTo("456");
        assertThat(onlySummary.serie()).isEqualTo("2");
        assertThat(onlySummary.diasRestantes()).isEqualTo(10);
        ManifestationDTOs.Note full = pending.notas().get(1);
        assertThat(full.xmlCompleto()).isTrue();
        assertThat(full.ciencia()).isTrue();
        assertThat(full.prazo()).isEqualTo(LocalDate.now().plusDays(80));
    }

    @Test
    void sendsTheConfirmationAndRecordsEachRegisteredEvent() throws Exception {
        SefazNoteSummary summary = new SefazNoteSummary();
        summary.setAccessKey(KEY_2);
        when(summaryRepository.findByCompany_IdAndAccessKey(company.getId(), KEY_2)).thenReturn(Optional.of(summary));
        when(eventClient.send(eq(certificate), eq(CNPJ), any())).thenReturn(new SefazEventResponse("128", "ok", List.of(
                new SefazEventResponse.Result(KEY_1, "135", "Evento registrado", "891", "2026-11-23T10:00:05-03:00"),
                new SefazEventResponse.Result(KEY_2, "573", "Duplicidade de evento", null, null))));

        ManifestationDTOs.Result result = service.send(request("confirmacao", null, KEY_1, KEY_2));

        ArgumentCaptor<List<EventRequest>> sent = ArgumentCaptor.captor();
        verify(eventClient).send(eq(certificate), eq(CNPJ), sent.capture());
        assertThat(sent.getValue()).extracting(EventRequest::type).containsOnly(Manifestation.CONFIRMACAO);
        assertThat(result.registradas()).isEqualTo(2);
        assertThat(result.recusadas()).isZero();
        ArgumentCaptor<FiscalEventData> event = ArgumentCaptor.captor();
        verify(eventService).record(eq(company), event.capture());
        assertThat(event.getValue().type()).isEqualTo("210200");
        assertThat(event.getValue().accessKey()).isEqualTo(KEY_1);
        assertThat(summary.getCienciaStatus()).isEqualTo(CienciaStatus.ENVIADA); // released: fetched by key next
    }

    @Test
    void aRefusalComesBackPerNote() throws Exception {
        when(eventClient.send(any(), any(), any())).thenReturn(new SefazEventResponse("128", "ok", List.of(
                new SefazEventResponse.Result(KEY_1, "596", "Evento apresentado apos o prazo", null, null))));

        ManifestationDTOs.Result result = service.send(request("DESCONHECIMENTO", null, KEY_1));

        assertThat(result.recusadas()).isEqualTo(1);
        assertThat(result.itens().get(0).codigo()).isEqualTo("596");
        assertThat(result.itens().get(0).mensagem()).isEqualTo("Evento apresentado apos o prazo");
        verify(eventService, never()).record(any(), any());
    }

    @Test
    void aConnectionFailureMarksTheBatch() throws Exception {
        when(eventClient.send(any(), any(), any())).thenThrow(new IOException("timeout"));

        ManifestationDTOs.Result result = service.send(request("CONFIRMACAO", null, KEY_1, KEY_2));

        assertThat(result.recusadas()).isEqualTo(2);
        assertThat(result.itens().get(0).mensagem()).contains("timeout");
    }

    @Test
    void notPerformedNeedsAJustification() {
        assertThatThrownBy(() -> service.send(request("NAO_REALIZADA", "curta", KEY_1)))
                .isInstanceOf(BadRequestException.class).hasMessage("error.sefaz.manifestation.justification");
        verifyNoInteractions(eventClient);
    }

    @Test
    void refusedWithoutTheServiceOrTheCertificateOrWithABadKey() {
        assertThatThrownBy(() -> service.send(request("CONFIRMACAO", null, "123")))
                .isInstanceOf(BadRequestException.class).hasMessage("error.sefaz.manifestation.notes");

        when(certificateService.resolve(company)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.send(request("CONFIRMACAO", null, KEY_1)))
                .isInstanceOf(BadRequestException.class).hasMessage("error.sefaz.manifestation.no.certificate");

        when(manifestService.manifests(company.getId())).thenReturn(false);
        assertThatThrownBy(() -> service.send(request("CONFIRMACAO", null, KEY_1)))
                .isInstanceOf(BadRequestException.class).hasMessage("error.sefaz.manifestation.not.in.plan");
        verifyNoInteractions(eventClient);
    }
}
