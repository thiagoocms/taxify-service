package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogDTO;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogFilterDTO;
import br.com.cshub.taxify.mapper.InvoiceAuditLogMapper;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
import br.com.cshub.taxify.search.InvoiceAuditLogSpecification;
import br.com.cshub.taxify.validation.InvoiceAuditLogValidation;
import jakarta.persistence.criteria.JoinType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class InvoiceAuditLogService extends AbstractCrudService<InvoiceAuditLog, UUID, InvoiceAuditLogDTO, InvoiceAuditLogFilterDTO> {

    private final TenantAccessService tenantAccessService;

    @Autowired
    protected InvoiceAuditLogService(InvoiceAuditLogRepository repository, InvoiceAuditLogMapper mapper, InvoiceAuditLogValidation validation, MessageService messageService, InvoiceAuditLogSpecification specification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.invoice.audit.log.not.found");
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    protected void checkAccess(InvoiceAuditLog entity) {
        tenantAccessService.assertCompanyAccess(entity.getCompany());
    }

    @Override
    protected Specification<InvoiceAuditLog> restrictQuery(Specification<InvoiceAuditLog> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<InvoiceAuditLog> restriction = (root, query, builder) ->
                builder.equal(root.join("company", JoinType.LEFT).get("accountingFirm").get("id"), firmId.get());
        spec = spec == null ? restriction : spec.and(restriction);
        return tenantAccessService.restrictToUserCompanies(spec, root -> root.get("company"));
    }
}
