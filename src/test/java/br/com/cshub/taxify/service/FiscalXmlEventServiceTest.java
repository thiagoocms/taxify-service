package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlEvent;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FiscalXmlEventServiceTest {

    private static final String KEY = "35241112345678000199550010000012341000012345";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    private final FiscalXmlEventRepository eventRepository = mock(FiscalXmlEventRepository.class);
    private final FiscalXmlDocumentRepository documentRepository = mock(FiscalXmlDocumentRepository.class);
    private final InvoiceAuditTrail auditTrail = mock(InvoiceAuditTrail.class);
    private final FiscalXmlEventService service = new FiscalXmlEventService(eventRepository, documentRepository, auditTrail);
    private final Company company = new Company(UUID.randomUUID());

    private static FiscalEventData cancellation(boolean registered) {
        return new FiscalEventData(KEY, "110111", "Cancelamento", 1, AT, "135260000123456", "Erro", registered);
    }

    private FiscalXmlDocument note() {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setCompany(company);
        document.setAccessKey(KEY);
        return document;
    }

    @Test
    void registeredCancellationCancelsTheNote() {
        FiscalXmlDocument note = note();
        when(documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), KEY)).thenReturn(Optional.of(note));

        assertThat(service.record(company, cancellation(true))).isTrue();

        assertThat(note.getStatus()).isEqualTo(FiscalDocumentStatus.CANCELED);
        assertThat(note.getCanceledAt()).isEqualTo(AT);
        verify(auditTrail).record(company, KEY, br.com.cshub.taxify.enumerated.InvoiceAuditStatus.CANCELED, "Cancelada na SEFAZ: Erro");
        ArgumentCaptor<FiscalXmlEvent> saved = ArgumentCaptor.forClass(FiscalXmlEvent.class);
        verify(eventRepository).save(saved.capture());
        assertThat(saved.getValue().getDocument()).isSameAs(note);
    }

    @Test
    void unregisteredCancellationIsKeptButChangesNothing() {
        FiscalXmlDocument note = note();
        when(documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), KEY)).thenReturn(Optional.of(note));

        service.record(company, cancellation(false));

        assertThat(note.getStatus()).isEqualTo(FiscalDocumentStatus.AUTHORIZED);
        verify(eventRepository).save(any(FiscalXmlEvent.class));
    }

    @Test
    void eventBeforeItsNoteIsAppliedWhenTheNoteArrives() {
        when(documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), KEY)).thenReturn(Optional.empty());
        service.record(company, cancellation(true));
        ArgumentCaptor<FiscalXmlEvent> pending = ArgumentCaptor.forClass(FiscalXmlEvent.class);
        verify(eventRepository).save(pending.capture());
        assertThat(pending.getValue().getDocument()).isNull();

        FiscalXmlDocument note = note();
        when(eventRepository.findByCompany_IdAndAccessKeyAndDocumentIsNull(company.getId(), KEY)).thenReturn(List.of(pending.getValue()));
        service.linkPendingEvents(note);

        assertThat(note.isCanceled()).isTrue();
        assertThat(pending.getValue().getDocument()).isSameAs(note);
    }

    @Test
    void knownEventIsIgnored() {
        when(eventRepository.existsByCompany_IdAndAccessKeyAndEventTypeAndSequenceNumber(eq(company.getId()), anyString(), anyString(), anyInt()))
                .thenReturn(true);

        assertThat(service.record(company, cancellation(true))).isFalse();
        verify(eventRepository, never()).save(any());
    }
}
