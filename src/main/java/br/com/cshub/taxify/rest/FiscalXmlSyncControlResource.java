package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.enumerated.Permission;
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
 *
 * @deprecated CP-RN-23 (spec 2026-11-27, P-CP-10): replaced by the capture panel - {@code GET /capture-panel}
 * ({@link CapturePanelResource}), which shows both sides, SEFAZ and ADN, the failures and the queue. Kept working as it
 * is, with the {@code Deprecation: true} header; removed in a cleanup after the panel's go-live.
 */
@Deprecated
@RequiresPermission(Permission.INVOICES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/fiscal-xml-sync-control")
public class FiscalXmlSyncControlResource {

    private final FiscalXmlSyncControlService service;

    public FiscalXmlSyncControlResource(FiscalXmlSyncControlService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<FiscalXmlSyncControlDTO>> findAll() {
        return ResponseEntity.ok().header("Deprecation", "true")
                .header("Link", "<" + AppConstants.API + AppConstants.V1 + "/capture-panel>; rel=\"successor-version\"")
                .body(service.findAll());
    }
}
