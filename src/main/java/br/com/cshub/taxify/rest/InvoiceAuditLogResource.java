package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogDTO;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogFilterDTO;
import br.com.cshub.taxify.service.InvoiceAuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RequiresPermission(value = Permission.INVOICES_VIEW, write = Permission.INVOICES_AUDIT)
@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/invoice-audit-log")
public class InvoiceAuditLogResource extends AbstractCrudResource<UUID, InvoiceAuditLogDTO, InvoiceAuditLogFilterDTO> {

    @Autowired
    protected InvoiceAuditLogResource(InvoiceAuditLogService service) {
        super(service);
    }
}
