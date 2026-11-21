package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlEvent;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEventDTO;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stores the SEFAZ events of a company's notes and applies what they mean: a registered
 * cancellation marks the note CANCELED. Works in any order - an event that arrives before
 * its note is kept unlinked and applied by {@link #linkPendingEvents} when the note arrives.
 */
@Service
public class FiscalXmlEventService {

    private static final int MAX_DESCRIPTION = 120;

    private final FiscalXmlEventRepository eventRepository;
    private final FiscalXmlDocumentRepository documentRepository;

    public FiscalXmlEventService(FiscalXmlEventRepository eventRepository, FiscalXmlDocumentRepository documentRepository) {
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
        FiscalXmlEvent event = new FiscalXmlEvent();
        event.setCompany(company);
        event.setAccessKey(data.accessKey());
        event.setEventType(data.type());
        event.setDescription(truncate(data.description()));
        event.setSequenceNumber(sequence);
        event.setEventAt(data.eventAt());
        event.setProtocol(data.protocol());
        event.setText(data.text());
        event.setRegistered(data.registered());
        event.setDeleted(false);

        documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), data.accessKey()).ifPresent(document -> {
            event.setDocument(document);
            apply(document, event);
        });
        eventRepository.save(event);
        return true;
    }

    /** A note just imported takes over the events that arrived before it. */
    @Transactional
    public void linkPendingEvents(FiscalXmlDocument document) {
        if (document.getAccessKey() == null) {
            return;
        }
        for (FiscalXmlEvent event : eventRepository.findByCompany_IdAndAccessKeyAndDocumentIsNull(
                document.getCompany().getId(), document.getAccessKey())) {
            event.setDocument(document);
            apply(document, event);
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

    private void apply(FiscalXmlDocument document, FiscalXmlEvent event) {
        FiscalEventData data = new FiscalEventData(event.getAccessKey(), event.getEventType(), event.getDescription(),
                event.getSequenceNumber(), event.getEventAt(), event.getProtocol(), event.getText(), event.isRegistered());
        if (data.isCancellation() && !document.isCanceled()) {
            document.setStatus(FiscalDocumentStatus.CANCELED);
            document.setCanceledAt(event.getEventAt());
            documentRepository.save(document);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_DESCRIPTION ? value : value.substring(0, MAX_DESCRIPTION);
    }
}
