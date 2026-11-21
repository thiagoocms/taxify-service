package br.com.cshub.taxify.rest;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.validation.Valid;
import br.com.cshub.taxify.service.assessment.FiscalPeriodClosingService;
import br.com.cshub.taxify.dto.assessment.GuideUpdateDTO;
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
    private final FiscalPeriodClosingService closingService;

    public TaxAssessmentResource(TaxAssessmentService service, FiscalPeriodClosingService closingService) {
        this.service = service;
        this.closingService = closingService;
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

    /** Closes the month: freezes the assessment as it is now. */
    @RequiresPermission(Permission.TAX_ASSESSMENT_CLOSE)
    @PostMapping("/{companyId}/fechamento")
    public ResponseEntity<TaxAssessmentDTO> close(@PathVariable UUID companyId, @RequestParam(required = false) String month) {
        return ResponseEntity.ok(closingService.close(companyId, month));
    }

    /** Reopens the month: it follows the notes again. */
    @RequiresPermission(Permission.TAX_ASSESSMENT_CLOSE)
    @DeleteMapping("/{companyId}/fechamento")
    public ResponseEntity<TaxAssessmentDTO> reopen(@PathVariable UUID companyId, @RequestParam(required = false) String month) {
        return ResponseEntity.ok(closingService.reopen(companyId, month));
    }

    /** The guide of the month (issued, amount, due date) and closing notes. */
    @RequiresPermission(Permission.TAX_ASSESSMENT_CLOSE)
    @PutMapping("/{companyId}/guia")
    public ResponseEntity<TaxAssessmentDTO> guide(@PathVariable UUID companyId, @RequestParam(required = false) String month,
                                                  @Valid @RequestBody GuideUpdateDTO guide) {
        return ResponseEntity.ok(closingService.updateGuide(companyId, month, guide));
    }
}
