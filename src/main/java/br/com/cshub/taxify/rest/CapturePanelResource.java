package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.capture.CaptureCompanyDTO;
import br.com.cshub.taxify.dto.capture.CapturePanelResponseDTO;
import br.com.cshub.taxify.dto.capture.CapturePriorityResponseDTO;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.AdminOnly;
import br.com.cshub.taxify.security.RequiresPermission;
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
 * Painel da captura de XML, visão do escritório (spec 2026-11-27, CP-RN-01): o ADMIN vê todas as empresas do próprio
 * escritório; o perfil com {@code INVOICES_SYNC} (FISCAL), só a carteira; os demais, 403. O escritório vem sempre do
 * usuário logado. Leitura liberada no escritório bloqueado; o "Sincronizar agora" é escrita (403 no somente leitura).
 */
@RequiresPermission(Permission.INVOICES_SYNC)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/capture-panel")
public class CapturePanelResource {

    private final CapturePanelQueryService queryService;
    private final CapturePriorityService priorityService;
    private final CapturePanelValidation validation;

    public CapturePanelResource(CapturePanelQueryService queryService, CapturePriorityService priorityService,
                                CapturePanelValidation validation) {
        this.queryService = queryService;
        this.priorityService = priorityService;
        this.validation = validation;
    }

    /**
     * @param situacao uma ou mais (repetido ou separado por vírgula): EM_DIA, PENDENTE, ATRASADA, COM_ERRO, NUNCA_TENTADA,
     *                 FORA_DO_PLANO, BLOQUEADA - sobre a situação geral, ou do canal informado
     * @param canal    SIEG, SEFAZ ou ADN
     * @param tipo     NFE_RECEBIDAS, NFE_EMITIDAS, EVENTOS ou NFSE
     * @param busca    parte do nome da empresa (sem diferenciar maiúsculas e acentos)
     * @param sort     status (padrão), name ou nextAttempt
     */
    @GetMapping
    public ResponseEntity<CapturePanelResponseDTO> panel(@RequestParam(required = false) List<String> situacao,
                                                         @RequestParam(required = false) String canal,
                                                         @RequestParam(required = false) String tipo,
                                                         @RequestParam(required = false) String busca,
                                                         @RequestParam(defaultValue = "false") boolean incluirInativas,
                                                         @RequestParam(required = false) String sort,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(queryService.firmPanel(
                validation.check(situacao, canal, tipo, busca, incluirInativas, sort, page, size)));
    }

    /** O detalhe da empresa (CP-RN-09); empresa de outro escritório ou fora da carteira: 404. */
    @GetMapping("/companies/{companyId}")
    public ResponseEntity<CaptureCompanyDTO> company(@PathVariable UUID companyId) {
        return ResponseEntity.ok(queryService.firmDetail(companyId));
    }

    /** "Sincronizar agora" (CP-RN-15 a CP-RN-17): 202 com o pedido; 403, 404, 409, 429 ou 503. Só o ADMIN. */
    @AdminOnly
    @PostMapping("/companies/{companyId}/sync-now")
    public ResponseEntity<CapturePriorityResponseDTO> syncNow(@PathVariable UUID companyId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(priorityService.request(companyId, false));
    }
}
