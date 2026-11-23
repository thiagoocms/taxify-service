package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.audit.AuditDTOs;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.audit.AuditService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Auditoria tipo malha (roadmap M9): the month's findings of a company, the run, the report and the rules. */
@RequiresPermission(Permission.INVOICES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/auditoria")
public class AuditResource {

    private final AuditService service;

    public AuditResource(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AuditDTOs.Report> report(@RequestParam String companyId, @RequestParam(required = false) String competencia) {
        return ResponseEntity.ok(service.report(companyId, competencia));
    }

    /** Audits now (the daily job does the previous and current month by itself). */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/executar")
    public ResponseEntity<AuditDTOs.Report> run(@RequestParam String companyId, @RequestParam(required = false) String competencia) {
        return ResponseEntity.ok(service.run(companyId, competencia));
    }

    @GetMapping("/laudo.pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam String companyId, @RequestParam(required = false) String competencia) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"laudo-auditoria-" + (competencia == null ? "" : competencia) + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(service.pdf(companyId, competencia));
    }

    @GetMapping("/laudo.csv")
    public ResponseEntity<byte[]> csv(@RequestParam String companyId, @RequestParam(required = false) String competencia) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"laudo-auditoria-" + (competencia == null ? "" : competencia) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(service.csv(companyId, competencia));
    }

    @GetMapping("/regras")
    public ResponseEntity<List<AuditDTOs.Rule>> rules() {
        return ResponseEntity.ok(service.rules());
    }

    /** The fiscal team checked the rule (conferida=false takes it back). */
    @RequiresPermission(Permission.FISCAL_RULES_MANAGE)
    @PostMapping("/regras/{code}/conferencia")
    public ResponseEntity<AuditDTOs.Rule> review(@PathVariable String code, @RequestParam(defaultValue = "true") boolean conferida) {
        return ResponseEntity.ok(service.review(code, conferida));
    }

    @RequiresPermission(Permission.FISCAL_RULES_MANAGE)
    @PostMapping("/regras/{code}/ativa")
    public ResponseEntity<AuditDTOs.Rule> enable(@PathVariable String code, @RequestParam boolean ativa) {
        return ResponseEntity.ok(service.enable(code, ativa));
    }
}
