package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.firmaccess.FirmAccessActionDTO;
import br.com.cshub.taxify.dto.firmaccess.FirmContactUpdateDTO;
import br.com.cshub.taxify.dto.firmaccess.FirmEventDTO;
import br.com.cshub.taxify.dto.firmaccess.PlatformFirmDTO;
import br.com.cshub.taxify.security.AllowedWhenBlocked;
import br.com.cshub.taxify.security.PlatformAdminOnly;
import br.com.cshub.taxify.service.firmaccess.PlatformFirmService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Console da plataforma (F1-RN-14, F1-RN-15): só o admin da plataforma (403 para os demais, inclusive o ADMIN de um
 * escritório comum). Lista os escritórios, aplica as ações de acesso e revisão e mostra a trilha de cada um.
 */
@PlatformAdminOnly
@AllowedWhenBlocked
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/platform/firms")
public class PlatformFirmResource {

    private final PlatformFirmService service;

    public PlatformFirmResource(PlatformFirmService service) {
        this.service = service;
    }

    /**
     * @param status     situação efetiva (TRIAL, ACTIVE, BLOCKED, SUSPENDED, CANCELED, PENDING_VERIFICATION); outra = 400
     * @param unreviewed true = só os liberados na migração ainda não revisados
     * @param q          parte do nome ou do CNPJ do escritório
     */
    @GetMapping
    public ResponseEntity<Page<PlatformFirmDTO>> list(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "false") boolean unreviewed,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(service.list(status, unreviewed, q, page, size));
    }

    /** extend-trial, convert-to-trial, release, suspend, reactivate, cancel ou mark-reviewed. */
    @PostMapping("/{id}/access/{action}")
    public ResponseEntity<PlatformFirmDTO> apply(@PathVariable UUID id, @PathVariable String action,
                                                 @RequestBody(required = false) FirmAccessActionDTO request) {
        return ResponseEntity.ok(service.apply(id, action, request));
    }

    /** F1-RN-13: CNPJ, e-mail financeiro e telefone do escritório (campo nulo = mantém). Devolve a linha do console. */
    @PutMapping("/{id}/contact")
    public ResponseEntity<PlatformFirmDTO> updateContact(@PathVariable UUID id,
                                                         @RequestBody(required = false) FirmContactUpdateDTO request) {
        return ResponseEntity.ok(service.updateContact(id, request));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<FirmEventDTO>> events(@PathVariable UUID id) {
        return ResponseEntity.ok(service.events(id));
    }
}
