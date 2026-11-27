package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.capture.CaptureCompanyDTO;
import br.com.cshub.taxify.dto.capture.CapturePanelResponseDTO;
import br.com.cshub.taxify.dto.capture.CapturePriorityResponseDTO;
import br.com.cshub.taxify.security.AllowedWhenBlocked;
import br.com.cshub.taxify.security.PlatformAdminOnly;
import br.com.cshub.taxify.service.capture.CapturePanelQueryService;
import br.com.cshub.taxify.service.capture.CapturePriorityService;
import br.com.cshub.taxify.validation.CapturePanelValidation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Painel da captura de XML, visão da plataforma (spec 2026-11-27, CP-RN-02): só o admin da plataforma (403 para os
 * demais, inclusive o ADMIN de escritório). Exceção limitada à F1-RN-15 (P-CP-01): nome da empresa, estabelecimento,
 * contagens e datas - nunca o CNPJ completo. Desligada ({@code TAXIFY_CAPTURE_PANEL_PLATFORM_ENABLED=false}, o padrão):
 * 404 em todas as rotas. Cada consulta grava {@code capture.panel.viewed scope=platform}.
 */
@PlatformAdminOnly
@AllowedWhenBlocked
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/platform/capture-panel")
public class PlatformCapturePanelResource {

    private final CapturePanelQueryService queryService;
    private final CapturePriorityService priorityService;
    private final CapturePanelValidation validation;

    public PlatformCapturePanelResource(CapturePanelQueryService queryService, CapturePriorityService priorityService,
                                        CapturePanelValidation validation) {
        this.queryService = queryService;
        this.priorityService = priorityService;
        this.validation = validation;
    }

    /** As mesmas queries do painel do escritório, mais {@code firmId}. */
    @GetMapping
    public ResponseEntity<CapturePanelResponseDTO> panel(@RequestParam(required = false) List<String> situacao,
                                                         @RequestParam(required = false) String canal,
                                                         @RequestParam(required = false) String tipo,
                                                         @RequestParam(required = false) String busca,
                                                         @RequestParam(defaultValue = "false") boolean incluirInativas,
                                                         @RequestParam(required = false) UUID firmId,
                                                         @RequestParam(required = false) String sort,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        queryService.assertPlatformEnabled();
        return ResponseEntity.ok(queryService.platformPanel(
                validation.check(situacao, canal, tipo, busca, incluirInativas, sort, page, size), firmId));
    }

    @GetMapping("/companies/{companyId}")
    public ResponseEntity<CaptureCompanyDTO> company(@PathVariable UUID companyId) {
        return ResponseEntity.ok(queryService.platformDetail(companyId));
    }

    /** "Sincronizar agora" para qualquer empresa; escritório bloqueado: 409 {@code error.capture.priority.firm.blocked}. */
    @PostMapping("/companies/{companyId}/sync-now")
    public ResponseEntity<CapturePriorityResponseDTO> syncNow(@PathVariable UUID companyId) {
        queryService.assertPlatformEnabled();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(priorityService.request(companyId, true));
    }
}
