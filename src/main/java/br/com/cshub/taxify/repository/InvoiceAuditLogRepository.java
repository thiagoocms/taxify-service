package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceAuditLogRepository extends AbstractJpaRepository<InvoiceAuditLog, UUID> {

    Optional<InvoiceAuditLog> findFirstByExternalInvoiceKeyAndStatusAndDeletedIsFalseOrderByCreatedDateDesc(
            String externalInvoiceKey, InvoiceAuditStatus status);
}
