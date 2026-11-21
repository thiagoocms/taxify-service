package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogDTO;
import br.com.cshub.taxify.dto.invoice.InvoiceAuditLogFilterDTO;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.InvoiceAuditLogService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The notes' audit trail - append-only: list, read and add a manual entry; nothing is edited
 * or deleted. Conferência, rectification and cancellation add their entries by themselves.
 */
@RequiresPermission(value = Permission.INVOICES_VIEW, write = Permission.INVOICES_AUDIT)
@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/invoice-audit-log")
public class InvoiceAuditLogResource {

    private final InvoiceAuditLogService service;

    public InvoiceAuditLogResource(InvoiceAuditLogService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Page<InvoiceAuditLogDTO>> getAll(Pageable pageable, InvoiceAuditLogFilterDTO filter) {
        return ResponseEntity.ok(service.findAll(pageable, filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceAuditLogDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /** A manual entry; the author is always the logged-in user. */
    @PostMapping
    public ResponseEntity<InvoiceAuditLogDTO> create(@Valid @RequestBody InvoiceAuditLogDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }
}
