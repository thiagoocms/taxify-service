package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.assessment.TaxAssessmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Apuração do período (faturamento, RBT12, estimativa de impostos); {@code month} as "2026-10", default the previous month. */
@RequiresPermission(Permission.TAX_ASSESSMENT_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/fiscal/apuracao")
public class TaxAssessmentResource {

    private final TaxAssessmentService service;

    public TaxAssessmentResource(TaxAssessmentService service) {
        this.service = service;
    }

    /** Every company of the firm (the user's portfolio) for the month. */
    @GetMapping
    public ResponseEntity<List<TaxAssessmentDTO>> firm(@RequestParam(required = false) String month) {
        return ResponseEntity.ok(service.assessFirm(month));
    }

    @GetMapping("/{companyId}")
    public ResponseEntity<TaxAssessmentDTO> company(@PathVariable UUID companyId, @RequestParam(required = false) String month) {
        return ResponseEntity.ok(service.assess(companyId, month));
    }
}
