package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.billing.ExternalLinkDTO;
import br.com.cshub.taxify.dto.billing.ExternalLinkLookupDTO;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.ExternalLinkService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The company's customer in the firm's billing provider - one link per company. */
@RequiresPermission(value = Permission.BILLING_VIEW, write = Permission.BILLING_MANAGE)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/billing/companies/{companyId}/link")
public class ExternalLinkResource {

    private final ExternalLinkService service;

    public ExternalLinkResource(ExternalLinkService service) {
        this.service = service;
    }

    /** 204 when the company isn't linked (or the firm bills manually). */
    @GetMapping
    public ResponseEntity<ExternalLinkDTO> get(@PathVariable UUID companyId) {
        return service.find(companyId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping
    public ResponseEntity<ExternalLinkDTO> save(@PathVariable UUID companyId, @Valid @RequestBody ExternalLinkDTO link) {
        return ResponseEntity.ok(service.save(companyId, link));
    }

    /** Looks the customer up in the provider by another CNPJ/CPF and links it. */
    @PostMapping("/lookup")
    public ResponseEntity<ExternalLinkDTO> lookup(@PathVariable UUID companyId, @Valid @RequestBody ExternalLinkLookupDTO request) {
        return ResponseEntity.ok(service.lookup(companyId, request.document()));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable UUID companyId) {
        service.delete(companyId);
        return ResponseEntity.noContent().build();
    }
}
