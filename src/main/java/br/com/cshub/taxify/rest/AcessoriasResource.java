package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.acessorias.AcessoriasImportResultDTO;
import br.com.cshub.taxify.dto.acessorias.AcessoriasUserSummaryDTO;
import br.com.cshub.taxify.dto.integration.SyncJobStatusDTO;
import br.com.cshub.taxify.service.AcessoriasImportService;
import br.com.cshub.taxify.service.AcessoriasUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Acessórias integration for the current user's firm: imports its companies (and links
 * their responsáveis) and lists its users - ADMIN only, see SecurityConfig.
 */
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/integrations/acessorias")
public class AcessoriasResource {

    private final AcessoriasImportService importService;
    private final AcessoriasUserService userService;

    public AcessoriasResource(AcessoriasImportService importService, AcessoriasUserService userService) {
        this.importService = importService;
        this.userService = userService;
    }

    /** Starts the company import in the background - 202 with the job status; poll {@code GET /import}. */
    @PostMapping("/import")
    public ResponseEntity<SyncJobStatusDTO<AcessoriasImportResultDTO>> startImport() {
        return ResponseEntity.accepted().body(importService.startForCurrentFirm());
    }

    @GetMapping("/import")
    public ResponseEntity<SyncJobStatusDTO<AcessoriasImportResultDTO>> importStatus() {
        return ResponseEntity.ok(importService.statusForCurrentFirm());
    }

    @GetMapping("/users")
    public ResponseEntity<List<AcessoriasUserSummaryDTO>> listUsers() {
        return ResponseEntity.ok(userService.listForCurrentFirm());
    }
}
