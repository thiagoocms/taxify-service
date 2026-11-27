package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.security.ReadOnlyOperation;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.fiscalxml.ServiceInvoiceDTOs;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.ServiceInvoiceQueryService;
import br.com.cshub.taxify.service.ServiceInvoiceService;
import br.com.cshub.taxify.validation.CompanyValidation;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Service invoices (NFS-e) of a company: list, totals, one note, its XML and a SIEG fetch for a period. */
@RequiresPermission(Permission.INVOICES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/notas-servico")
public class ServiceInvoiceResource {

    private static final Logger log = LoggerFactory.getLogger(ServiceInvoiceResource.class);
    /** Companies with a manual SIEG fetch running - a second click doesn't double it. */
    private final Set<UUID> fetching = ConcurrentHashMap.newKeySet();

    private final ServiceInvoiceQueryService queryService;
    private final ServiceInvoiceService service;
    private final CompanyValidation companyValidation;

    public ServiceInvoiceResource(ServiceInvoiceQueryService queryService, ServiceInvoiceService service,
                                  CompanyValidation companyValidation) {
        this.queryService = queryService;
        this.service = service;
        this.companyValidation = companyValidation;
    }

    @ReadOnlyOperation
    @PostMapping("/buscar")
    public ResponseEntity<Page<ServiceInvoiceDTOs.Summary>> search(@Valid @RequestBody ServiceInvoiceDTOs.Filter filter) {
        return ResponseEntity.ok(queryService.search(filter));
    }

    @ReadOnlyOperation
    @PostMapping("/totais")
    public ResponseEntity<ServiceInvoiceDTOs.Totals> totals(@Valid @RequestBody ServiceInvoiceDTOs.Filter filter) {
        return ResponseEntity.ok(queryService.totals(filter));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceInvoiceDTOs.Detail> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(queryService.detail(id));
    }

    @GetMapping("/{id}/xml")
    public ResponseEntity<byte[]> xml(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nfse-" + id + ".xml\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(queryService.xml(id));
    }

    /**
     * Fetches the company's NFS-e of the period from SIEG in the background (SIEG allows 2 requests a
     * minute, so a long period takes a while); the daily sync keeps the recent weeks up to date anyway.
     */
    @RequiresPermission(Permission.INVOICES_SYNC)
    @PostMapping("/sincronizar")
    public ResponseEntity<Map<String, Object>> fetch(@RequestParam String companyId,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        UUID id = companyValidation.checkExist(UUID.fromString(companyId.strip())).getId();
        if (!fetching.add(id)) {
            return ResponseEntity.ok(Map.of("iniciado", false));
        }
        Thread.ofVirtual().name("nfse-fetch-" + id).start(() -> {
            try {
                service.syncFromSieg(id, inicio, fim);
            } catch (RuntimeException e) {
                log.warn("Manual NFS-e fetch failed for company {}", id, e);
            } finally {
                fetching.remove(id);
            }
        });
        return ResponseEntity.ok(Map.of("iniciado", true));
    }
}
