package br.com.cshub.taxify.service;

import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Conferência of notes by the fiscal team: who checked which note, when, and what is wrong with it. */
@Service
public class FiscalXmlReviewService {

    private final FiscalXmlDocumentRepository documentRepository;
    private final TenantAccessService tenantAccessService;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;
    private final InvoiceAuditTrail auditTrail;

    public FiscalXmlReviewService(FiscalXmlDocumentRepository documentRepository, TenantAccessService tenantAccessService,
                                  CurrentUserService currentUserService, MessageService messageService, InvoiceAuditTrail auditTrail) {
        this.auditTrail = auditTrail;
        this.documentRepository = documentRepository;
        this.tenantAccessService = tenantAccessService;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
    }

    /**
     * Same status for every note; all or nothing - a note the user can't see fails the
     * whole call as not found.
     *
     * @return notes updated
     */
    @Transactional
    public int review(Collection<UUID> documentIds, ReviewStatus status, String note) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BadRequestException(messageService.getMessage("error.review.documents.required"));
        }
        if (status == ReviewStatus.DIVERGENT && (note == null || note.isBlank())) {
            throw new BadRequestException(messageService.getMessage("error.review.note.required"));
        }
        List<FiscalXmlDocument> documents = documentRepository.findAllById(documentIds).stream()
                .filter(document -> !document.isDeleted())
                .toList();
        if (documents.size() != documentIds.stream().distinct().count()) {
            throw new ResourceNotFoundException(messageService.getMessage("error.entity.not.found"));
        }
        documents.forEach(document -> tenantAccessService.assertCompanyAccess(document.getCompany()));

        String by = currentUserService.getCurrentUser().map(User::getLogin).orElse("system");
        LocalDateTime now = LocalDateTime.now();
        documents.forEach(document -> document.review(status, by, note, now));
        documentRepository.saveAll(documents);
        InvoiceAuditStatus auditStatus = switch (status) {
            case REVIEWED -> InvoiceAuditStatus.OK;
            case DIVERGENT -> InvoiceAuditStatus.DIVERGENT;
            case PENDING -> InvoiceAuditStatus.REVIEW_REOPENED;
        };
        documents.forEach(document -> auditTrail.record(document.getCompany(), noteKey(document), auditStatus, note));
        return documents.size();
    }

    /** The access key; a note without one (manual upload of an odd file) is identified by its id. */
    static String noteKey(FiscalXmlDocument document) {
        return document.getAccessKey() != null ? document.getAccessKey() : document.getId().toString();
    }
}
