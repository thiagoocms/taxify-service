package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
import org.springframework.stereotype.Service;

/**
 * Writes the notes' audit trail - what happened to a note, when and by whom (null user = a
 * background job). The trail is append-only: entries are never edited or removed.
 */
@Service
public class InvoiceAuditTrail {

    private static final int NOTES_MAX = 500;

    private final InvoiceAuditLogRepository repository;
    private final CurrentUserService currentUserService;

    public InvoiceAuditTrail(InvoiceAuditLogRepository repository, CurrentUserService currentUserService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    public InvoiceAuditLog record(Company company, String noteKey, InvoiceAuditStatus status, String notes) {
        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setCompany(company);
        log.setExternalInvoiceKey(noteKey);
        log.setStatus(status);
        String text = notes == null || notes.isBlank() ? status.name() : notes.strip();
        log.setNotes(text.length() > NOTES_MAX ? text.substring(0, NOTES_MAX) : text);
        log.setDeleted(false);
        currentUserService.getCurrentUser().ifPresent(log::setUser);
        return repository.save(log);
    }
}
