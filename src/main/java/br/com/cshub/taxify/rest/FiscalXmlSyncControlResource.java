package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSyncControlDTO;
import br.com.cshub.taxify.service.FiscalXmlSyncControlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only: which companies are fully synced with SIEG and which still have notes
 * pending, per {@link br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl}.
 */
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/fiscal-xml-sync-control")
public class FiscalXmlSyncControlResource {

    private final FiscalXmlSyncControlService service;

    public FiscalXmlSyncControlResource(FiscalXmlSyncControlService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<FiscalXmlSyncControlDTO>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }
}
