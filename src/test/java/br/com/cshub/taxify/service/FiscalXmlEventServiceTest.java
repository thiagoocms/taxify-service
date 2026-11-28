package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentEvent;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.repository.FiscalDocumentEventRepository;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.support.TestNotes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    private static final String KEY = TestNotes.key44("55", 1234);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    private final FiscalDocumentEventRepository eventRepository = mock(FiscalDocumentEventRepository.class);
    private final FiscalDocumentRepository documentRepository = mock(FiscalDocumentRepository.class);
    private final InvoiceAuditTrail auditTrail = mock(InvoiceAuditTrail.class);
    private final FiscalXmlEventService service = new FiscalXmlEventService(eventRepository, documentRepository, auditTrail);
    private final Company company = TestNotes.company(TestNotes.COMPANY_CNPJ);

    private static FiscalEventData cancellation(boolean registered) {
        return new FiscalEventData(KEY, "110111", "Cancelamento", 1, AT, "135260000123456", "Erro", registered);
    }

    private GoodsInvoice note() {
        return TestNotes.note(company).key(KEY).build();
    }

    @Test
    void registeredCancellationCancelsTheNote() {
        GoodsInvoice note = note();
        when(documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), KEY)).thenReturn(Optional.of(note));

        assertThat(service.record(company, cancellation(true))).isTrue();

        assertThat(note.getStatus()).isEqualTo(FiscalDocumentStatus.CANCELED);
        assertThat(note.getCanceledAt()).isEqualTo(AT);
        verify(auditTrail).record(company, KEY, InvoiceAuditStatus.CANCELED, "Cancelada na SEFAZ: Erro");
        ArgumentCaptor<FiscalDocumentEvent> saved = ArgumentCaptor.forClass(FiscalDocumentEvent.class);
        verify(eventRepository).save(saved.capture());
        assertThat(saved.getValue().getDocument()).isSameAs(note);
    }

    @Test
    void unregisteredCancellationIsKeptButChangesNothing() {
        GoodsInvoice note = note();
        when(documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), KEY)).thenReturn(Optional.of(note));

        service.record(company, cancellation(false));

        assertThat(note.getStatus()).isEqualTo(FiscalDocumentStatus.AUTHORIZED);
        verify(eventRepository).save(any(FiscalDocumentEvent.class));
    }

    @Test
    void eventBeforeItsNoteIsAppliedWhenTheNoteArrives() {
        when(documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), KEY)).thenReturn(Optional.empty());
        service.record(company, cancellation(true));
        ArgumentCaptor<FiscalDocumentEvent> pending = ArgumentCaptor.forClass(FiscalDocumentEvent.class);
        verify(eventRepository).save(pending.capture());
        assertThat(pending.getValue().getDocument()).isNull();

        GoodsInvoice note = note();
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
