package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentEvent;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Events of the fiscal documents of any type (FD-RN-07), unique by company, access key, type and sequence. */
@Repository
public interface FiscalDocumentEventRepository extends AbstractJpaRepository<FiscalDocumentEvent, UUID> {

    boolean existsByCompany_IdAndAccessKeyAndEventTypeAndSequenceNumber(UUID companyId, String accessKey, String eventType,
                                                                       int sequenceNumber);

    /** Events that arrived before their document - linked when it's imported. */
    List<FiscalDocumentEvent> findByCompany_IdAndAccessKeyAndDocumentIsNull(UUID companyId, String accessKey);

    List<FiscalDocumentEvent> findByDocument_IdOrderByEventAtAscSequenceNumberAsc(UUID documentId);

    /** Registered events of these notes - which manifestations each one already has. */
    List<FiscalDocumentEvent> findByCompany_IdAndAccessKeyInAndRegisteredIsTrue(UUID companyId, Collection<String> accessKeys);
}
