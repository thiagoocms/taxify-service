package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogDTO;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InvoiceAuditLogMapper extends AbstractMapper<InvoiceAuditLog, InvoiceAuditLogDTO> {

    @Override
    protected InvoiceAuditLog toEntity(InvoiceAuditLogDTO source) {
        InvoiceAuditLog entity = new InvoiceAuditLog();
        entity.setUser(source.userId() != null ? new User(UUID.fromString(source.userId())) : null);
        entity.setCompany(source.companyId() != null ? new Company(UUID.fromString(source.companyId())) : null);
        entity.setExternalInvoiceKey(source.externalInvoiceKey());
        entity.setStatus(InvoiceAuditStatus.valueOf(String.valueOf(source.status())));
        entity.setNotes(source.notes());
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected InvoiceAuditLogDTO toModel(InvoiceAuditLog source) {
        return new InvoiceAuditLogDTO(
                source.getId().toString(),
                source.getExternalInvoiceKey(),
                source.getUser() != null ? source.getUser().getId().toString() : null,
                source.getCompany().getId().toString(),
                source.getStatus().toString(),
                source.getNotes(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(InvoiceAuditLog source, InvoiceAuditLog target) {

    }

}
