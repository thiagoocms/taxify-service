package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.contaazul.ContaAzulAuthorizeUrlDTO;
import br.com.cshub.taxify.dto.contaazul.ContaAzulCallbackDTO;
import br.com.cshub.taxify.dto.contaazul.ContaAzulConnectionStatusDTO;
import br.com.cshub.taxify.service.ContaAzulAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conta Azul OAuth2 connection of the current user's firm - ADMIN only, see SecurityConfig.
 * The callback is a regular authenticated POST: Conta Azul redirects the browser to the
 * front-end, which relays code + state with the user's own session.
 */
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/integrations/conta-azul")
public class ContaAzulResource {

    private final ContaAzulAuthService authService;

    public ContaAzulResource(ContaAzulAuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/authorize-url")
    public ResponseEntity<ContaAzulAuthorizeUrlDTO> authorizeUrl() {
        return ResponseEntity.ok(authService.buildAuthorizeUrl());
    }

    @PostMapping("/callback")
    public ResponseEntity<ContaAzulConnectionStatusDTO> callback(@Valid @RequestBody ContaAzulCallbackDTO body) {
        return ResponseEntity.ok(authService.completeAuthorization(body.code(), body.state()));
    }

    @GetMapping("/status")
    public ResponseEntity<ContaAzulConnectionStatusDTO> status() {
        return ResponseEntity.ok(authService.status());
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect() {
        authService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
