package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.retention.RetentionDTOs;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.retention.RetentionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Retentions of a competence (roadmap M1): to pay, to offset and informative - one company or the portfolio. */
@RequiresPermission(Permission.INVOICES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/retencoes")
public class RetentionResource {

    private final RetentionService service;

    public RetentionResource(RetentionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<RetentionDTOs.Screen> screen(@RequestParam(required = false) String companyId,
                                                       @RequestParam(required = false) String competencia,
                                                       @RequestParam(required = false) String natureza,
                                                       @RequestParam(required = false) String busca) {
        return ResponseEntity.ok(service.screen(companyId, competencia, natureza, busca));
    }

    @GetMapping("/csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required = false) String companyId,
                                      @RequestParam(required = false) String competencia,
                                      @RequestParam(required = false) String natureza,
                                      @RequestParam(required = false) String busca) {
        byte[] body = service.csv(companyId, competencia, natureza, busca);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"retencoes-"
                        + (competencia == null ? "" : competencia) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /** Marks or unmarks as checked. */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/conferencia")
    public ResponseEntity<Integer> review(@Valid @RequestBody RetentionDTOs.ReviewRequest request) {
        return ResponseEntity.ok(service.review(request));
    }
}
