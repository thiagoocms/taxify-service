package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.password.PasswordConfigDTO;
import br.com.cshub.taxify.dto.password.PasswordForgotRequestDTO;
import br.com.cshub.taxify.dto.password.PasswordResetCheckRequestDTO;
import br.com.cshub.taxify.dto.password.PasswordResetRequestDTO;
import br.com.cshub.taxify.security.AllowedWhenBlocked;
import br.com.cshub.taxify.security.ClientIpResolver;
import br.com.cshub.taxify.service.password.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Esqueci a senha" (spec 2026-11-27, SN-RN-01 a SN-RN-06 e SN-RN-14). Anônimo (permitAll na SecurityConfig) e sempre
 * liberado no escritório bloqueado (SN-RN-07). Com {@code TAXIFY_PASSWORD_RESET_ENABLED=false}, os POST respondem 503
 * {@code error.password.reset.unavailable} e a configuração diz {@code available=false}.
 */
@AllowedWhenBlocked
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/auth/password")
public class PasswordResetResource {

    private final PasswordResetService service;
    private final ClientIpResolver clientIpResolver;

    public PasswordResetResource(PasswordResetService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    /** {@code {available, resetMinutes, minLength}}. */
    @GetMapping("/config")
    public ResponseEntity<PasswordConfigDTO> config() {
        return ResponseEntity.ok(service.config());
    }

    /**
     * Sempre 202 sem corpo - exista ou não a conta, acima ou não dos limites, e com {@code identifier} vazio. O
     * processamento roda depois da resposta (o tempo não revela a conta); o IP é resolvido antes.
     */
    @PostMapping("/forgot")
    public ResponseEntity<Void> forgot(@RequestBody(required = false) PasswordForgotRequestDTO request, HttpServletRequest http) {
        service.requestReset(request == null ? null : request.identifier(), clientIpResolver.resolve(http));
        return ResponseEntity.accepted().build();
    }

    /** 204 se o link vale; 400 {@code error.password.reset.token.invalid}. Não consome o token. */
    @PostMapping("/reset/check")
    public ResponseEntity<Void> check(@RequestBody(required = false) PasswordResetCheckRequestDTO request) {
        service.check(request == null ? null : request.token());
        return ResponseEntity.noContent().build();
    }

    /** 204; 400 com o link inválido ou com o código da regra de senha (o link continua valendo nesse caso). */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@RequestBody(required = false) PasswordResetRequestDTO request) {
        service.reset(request == null ? null : request.token(), request == null ? null : request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
