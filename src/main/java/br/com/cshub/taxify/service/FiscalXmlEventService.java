package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentEvent;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEventDTO;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.repository.FiscalDocumentEventRepository;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stores the SEFAZ events of a company's documents and applies what they mean (FD-RN-07): a registered
 * cancellation marks the document CANCELED. Works in any order - an event that arrives before
 * its document is kept unlinked and applied by {@link #linkPendingEvents} when the document arrives.
 */
@Service
public class FiscalXmlEventService {

    private final FiscalDocumentEventRepository eventRepository;
    private final FiscalDocumentRepository documentRepository;
    private final InvoiceAuditTrail auditTrail;

    public FiscalXmlEventService(FiscalDocumentEventRepository eventRepository, FiscalDocumentRepository documentRepository,
                                 InvoiceAuditTrail auditTrail) {
        this.auditTrail = auditTrail;
        this.eventRepository = eventRepository;
        this.documentRepository = documentRepository;
    }

    /** @return true when the event is new for this company (already-known events are ignored) */
    @Transactional
    public boolean record(Company company, FiscalEventData data) {
        int sequence = data.sequence() == null ? 1 : data.sequence();
        if (data.type() == null
                || eventRepository.existsByCompany_IdAndAccessKeyAndEventTypeAndSequenceNumber(company.getId(), data.accessKey(), data.type(), sequence)) {
            return false;
        }
        FiscalDocumentEvent event = new FiscalDocumentEvent(company, data.accessKey(), data.type(), sequence,
                data.description(), data.eventAt(), data.protocol(), data.text(), data.registered());
        event.setDeleted(false);

        documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), data.accessKey())
                .ifPresent(document -> link(document, event));
        eventRepository.save(event);
        return true;
    }

    /** A document just imported takes over the events that arrived before it. */
    @Transactional
    public void linkPendingEvents(FiscalDocument document) {
        if (document.getAccessKey() == null) {
            return;
        }
        for (FiscalDocumentEvent event : eventRepository.findByCompany_IdAndAccessKeyAndDocumentIsNull(
                document.getCompany().getId(), document.getAccessKey())) {
            link(document, event);
            eventRepository.save(event);
        }
    }

    @Transactional(readOnly = true)
    public List<FiscalXmlEventDTO> eventsOf(UUID documentId) {
        return eventRepository.findByDocument_IdOrderByEventAtAscSequenceNumberAsc(documentId).stream()
                .map(event -> new FiscalXmlEventDTO(event.getEventType(), event.getDescription(), event.getSequenceNumber(),
                        event.getEventAt(), event.getProtocol(), event.getText(), event.isRegistered()))
                .toList();
    }

    /** Links the event and applies it: a registered cancellation cancels the document at the event time. */
    private void link(FiscalDocument document, FiscalDocumentEvent event) {
        if (event.linkTo(document)) {
            documentRepository.save(document);
            auditTrail.record(document.getCompany(), event.getAccessKey(), InvoiceAuditStatus.CANCELED,
                    "Cancelada na SEFAZ" + (event.getText() == null ? "" : ": " + event.getText()));
        }
    }
}
