package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.report.ReportDTOs;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.report.ReportPdf;
import br.com.cshub.taxify.service.report.ReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/** Reports and simulation (roadmap M12): monthly report (JSON and PDF), purchases' curva ABC and the regimes simulation. */
@RequiresPermission(Permission.TAX_ASSESSMENT_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/relatorios")
public class ReportResource {

    private final ReportService service;

    public ReportResource(ReportService service) {
        this.service = service;
    }

    @GetMapping("/mensal")
    public ResponseEntity<ReportDTOs.Monthly> monthly(@RequestParam String companyId, @RequestParam(required = false) String competencia) {
        return ResponseEntity.ok(service.monthly(companyId, competencia));
    }

    @GetMapping("/mensal.pdf")
    public ResponseEntity<byte[]> monthlyPdf(@RequestParam String companyId, @RequestParam(required = false) String competencia) {
        ReportDTOs.Monthly report = service.monthly(companyId, competencia);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"relatorio-" + report.competence() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(ReportPdf.monthly(report));
    }

    /** @param por PRODUTO (default) or FORNECEDOR; {@code meses} 1 to 24 (default 12) ending at the competence */
    @GetMapping("/curva-abc")
    public ResponseEntity<ReportDTOs.Abc> abc(@RequestParam String companyId, @RequestParam(required = false) String competencia,
                                              @RequestParam(required = false) String por, @RequestParam(required = false) Integer meses) {
        return ResponseEntity.ok(service.abc(companyId, competencia, por, meses));
    }

    /** @param margem profit margin in % for the Lucro Real (default 10); {@code iss} ISS rate in % outside the Simples (default 5) */
    @GetMapping("/simulacao")
    public ResponseEntity<ReportDTOs.Simulation> simulate(@RequestParam String companyId, @RequestParam(required = false) String competencia,
                                                          @RequestParam(required = false) BigDecimal margem,
                                                          @RequestParam(required = false) BigDecimal iss) {
        return ResponseEntity.ok(service.simulate(companyId, competencia, margem, iss));
    }
}
