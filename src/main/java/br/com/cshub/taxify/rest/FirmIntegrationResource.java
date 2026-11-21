package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.integration.FirmIntegrationApiKeyDTO;
import br.com.cshub.taxify.dto.integration.FirmIntegrationStatusDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.service.FirmIntegrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Integrations (SIEG, Acessórias, Conta Azul) of the current user's firm - credentials go
 * in, only masked status comes out. Conta Azul connects through /integrations/conta-azul.
 * GET is open to the firm's users (the screens use it to know what's available); writing is
 * ADMIN only, see SecurityConfig.
 */
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/integrations")
public class FirmIntegrationResource {

    private final FirmIntegrationService service;

    public FirmIntegrationResource(FirmIntegrationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<FirmIntegrationStatusDTO>> list() {
        return ResponseEntity.ok(service.listForCurrentFirm());
    }

    @PutMapping("/{type}/api-key")
    public ResponseEntity<FirmIntegrationStatusDTO> saveApiKey(@PathVariable IntegrationType type,
                                                               @RequestBody FirmIntegrationApiKeyDTO body) {
        return ResponseEntity.ok(service.saveApiKeyForCurrentFirm(type, body.apiKey()));
    }
}
