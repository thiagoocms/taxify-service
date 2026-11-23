package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.pendency.PendencyDTOs;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.pendency.PendencyScheduler;
import br.com.cshub.taxify.service.pendency.PendencyService;
import br.com.cshub.taxify.validation.CompanyValidation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pendencies of the portfolio or of one company (roadmap M13). */
@RequiresPermission(Permission.INVOICES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/pendencias")
public class PendencyResource {

    private final PendencyService service;
    private final PendencyScheduler scheduler;
    private final CompanyValidation companyValidation;

    public PendencyResource(PendencyService service, PendencyScheduler scheduler, CompanyValidation companyValidation) {
        this.service = service;
        this.scheduler = scheduler;
        this.companyValidation = companyValidation;
    }

    /** Most severe first. status defaults to ABERTA; "TODAS" = any status. */
    @GetMapping
    public ResponseEntity<Page<PendencyDTOs.Pendency>> list(@RequestParam(required = false) String companyId,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) PendencySeverity severity,
                                                            @RequestParam(required = false) PendencyType type,
                                                            @RequestParam(required = false) String competence,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "50") int size) {
        PendencyStatus parsed = status == null || status.isBlank() ? PendencyStatus.ABERTA
                : "TODAS".equalsIgnoreCase(status) ? null : PendencyStatus.valueOf(status.strip().toUpperCase());
        return ResponseEntity.ok(service.list(companyId, parsed, severity, type, competence, page, size));
    }

    /** Open pendencies per type and severity. */
    @GetMapping("/resumo")
    public ResponseEntity<PendencyDTOs.Summary> summary(@RequestParam(required = false) String companyId) {
        return ResponseEntity.ok(service.summary(companyId));
    }

    /** The SEFAZ capture of each company: last run and the last 7 days. */
    @GetMapping("/integracoes")
    public ResponseEntity<List<PendencyDTOs.IntegrationHealth>> health(@RequestParam(required = false) String companyId) {
        return ResponseEntity.ok(service.health(companyId));
    }

    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/{id}/resolver")
    public ResponseEntity<PendencyDTOs.Pendency> resolve(@PathVariable UUID id, @Valid @RequestBody PendencyDTOs.CloseRequest request) {
        return ResponseEntity.ok(service.close(id, PendencyStatus.RESOLVIDA, request.comentario()));
    }

    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/{id}/desconsiderar")
    public ResponseEntity<PendencyDTOs.Pendency> dismiss(@PathVariable UUID id, @Valid @RequestBody PendencyDTOs.CloseRequest request) {
        return ResponseEntity.ok(service.close(id, PendencyStatus.DESCONSIDERADA, request.comentario()));
    }

    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/{id}/reabrir")
    public ResponseEntity<PendencyDTOs.Pendency> reopen(@PathVariable UUID id) {
        return ResponseEntity.ok(service.reopen(id));
    }

    /** Recomputes now: one company right away, or the whole portfolio in the background. */
    @PostMapping("/atualizar")
    public ResponseEntity<Map<String, Object>> refresh(@RequestParam(required = false) String companyId) {
        if (companyId != null && !companyId.isBlank()) {
            int open = service.refresh(companyValidation.checkExist(UUID.fromString(companyId.strip())));
            return ResponseEntity.ok(Map.of("abertas", open, "emSegundoPlano", false));
        }
        return ResponseEntity.ok(Map.of("emSegundoPlano", scheduler.runInBackground()));
    }
}
