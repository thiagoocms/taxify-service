package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
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
public class InvoiceAuditLogService extends AbstractCrudService<InvoiceAuditLog, UUID, InvoiceAuditLogDTO, InvoiceAuditLogFilterDTO> {


    @Autowired
    protected InvoiceAuditLogService(InvoiceAuditLogRepository repository, InvoiceAuditLogMapper mapper, InvoiceAuditLogValidation validation, MessageService messageService, InvoiceAuditLogSpecification specification) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.invoice.audit.log.not.found");
    }

}
