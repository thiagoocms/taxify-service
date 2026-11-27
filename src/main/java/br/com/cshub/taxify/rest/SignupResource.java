package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.signup.SignupConfigDTO;
import br.com.cshub.taxify.dto.signup.SignupRequestDTO;
import br.com.cshub.taxify.dto.signup.SignupResendRequestDTO;
import br.com.cshub.taxify.dto.signup.SignupResponseDTO;
import br.com.cshub.taxify.dto.signup.SignupVerifyRequestDTO;
import br.com.cshub.taxify.dto.signup.SignupVerifyResponseDTO;
import br.com.cshub.taxify.security.AllowedWhenBlocked;
import br.com.cshub.taxify.security.ClientIpResolver;
import br.com.cshub.taxify.service.firmaccess.EmailVerificationService;
import br.com.cshub.taxify.service.firmaccess.SignupService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro público do escritório (anônimo - ver SecurityConfig): o cadastro, a verificação do e-mail, o reenvio do
 * link e a configuração que a tela de login lê para mostrar ou esconder "Criar conta" (F1-RN-01, F1-RN-03, F1-RN-11).
 */
@AllowedWhenBlocked
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/signup")
public class SignupResource {

    private final SignupService signupService;
    private final EmailVerificationService verificationService;
    private final ClientIpResolver clientIpResolver;

    public SignupResource(SignupService signupService, EmailVerificationService verificationService,
                          ClientIpResolver clientIpResolver) {
        this.signupService = signupService;
        this.verificationService = verificationService;
        this.clientIpResolver = clientIpResolver;
    }

    /** Se o cadastro está aberto e os links dos termos; com {@code available=false} o front esconde "Criar conta". */
    @GetMapping("/config")
    public ResponseEntity<SignupConfigDTO> config() {
        return ResponseEntity.ok(signupService.config());
    }

    /** 201 PENDING_VERIFICATION; 503 com o cadastro fechado; 400/409/429 conforme a validação. */
    @PostMapping
    public ResponseEntity<SignupResponseDTO> signup(@RequestBody SignupRequestDTO request,
                                                            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(signupService.signup(request, clientIpResolver.resolve(http)));
    }

    /** O clique no link: começa o teste; 400 {@code error.signup.token.invalid} com o link vencido ou já usado. */
    @PostMapping("/verify")
    public ResponseEntity<SignupVerifyResponseDTO> verify(@RequestBody SignupVerifyRequestDTO request) {
        return ResponseEntity.ok(verificationService.verify(request == null ? null : request.token()));
    }

    /** Sempre 202, exista ou não o login, e também acima dos limites por hora e do teto diário (nada é enviado). */
    @PostMapping("/resend")
    public ResponseEntity<Void> resend(@RequestBody(required = false) SignupResendRequestDTO request,
                                       HttpServletRequest http) {
        verificationService.resend(request == null ? null : request.login(), clientIpResolver.resolve(http));
        return ResponseEntity.accepted().build();
    }
}
