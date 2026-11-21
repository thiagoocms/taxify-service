package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.FiscalXmlEvent;

import java.util.List;
import java.util.UUID;

public interface FiscalXmlEventRepository extends AbstractJpaRepository<FiscalXmlEvent, UUID> {

    boolean existsByCompany_IdAndAccessKeyAndEventTypeAndSequenceNumber(UUID companyId, String accessKey, String eventType,
                                                                       int sequenceNumber);

    /** Events that arrived before their note - linked when it's imported. */
    List<FiscalXmlEvent> findByCompany_IdAndAccessKeyAndDocumentIsNull(UUID companyId, String accessKey);

    List<FiscalXmlEvent> findByDocument_IdOrderByEventAtAscSequenceNumberAsc(UUID documentId);
}
