package br.com.cshub.taxify.service;

import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.common.CompanyScopedCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogDTO;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogFilterDTO;
import br.com.cshub.taxify.mapper.InvoiceAuditLogMapper;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
import br.com.cshub.taxify.search.InvoiceAuditLogSpecification;
import br.com.cshub.taxify.validation.InvoiceAuditLogValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class InvoiceAuditLogService extends CompanyScopedCrudService<InvoiceAuditLog, UUID, InvoiceAuditLogDTO, InvoiceAuditLogFilterDTO> {


    @Autowired
    protected InvoiceAuditLogService(InvoiceAuditLogRepository repository, InvoiceAuditLogMapper mapper, InvoiceAuditLogValidation validation, MessageService messageService, InvoiceAuditLogSpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                InvoiceAuditLog::getCompany, root -> root.get("company"));
        setMessageNotFound("error.invoice.audit.log.not.found");
    }

    /** The trail is append-only. */
    @Override
    public InvoiceAuditLogDTO update(UUID id, InvoiceAuditLogDTO model) {
        throw new BadRequestException(messageService.getMessage("error.auditLog.immutable"));
    }

    @Override
    public void delete(UUID id) {
        throw new BadRequestException(messageService.getMessage("error.auditLog.immutable"));
    }


}
