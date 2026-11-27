package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.auth.Auth;
import br.com.cshub.taxify.dto.auth.AuthLoginDTO;
import br.com.cshub.taxify.dto.auth.AuthenticatedDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ForbiddenException;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Base64;

@Service
public class AuthService {

    private final TokenService tokenService;
    private final AuthenticationManager manager;
    private final MessageService messageService;
    private final FirmAccessService firmAccessService;


    @Autowired
    public AuthService(TokenService tokenService, AuthenticationManager manager, MessageService messageService,
                       FirmAccessService firmAccessService) {
        this.firmAccessService = firmAccessService;
        this.tokenService = tokenService;
        this.manager = manager;
        this.messageService = messageService;
    }

    public AuthenticatedDTO auth(AuthLoginDTO dto) {
        String[] decrypt = splitCredentials(dto.getAuth());
        if (decrypt.length < 2) {
            throw new BadRequestException();
        }
        var login = decrypt[0];
        var password = decrypt[1];
        if ((login == null || login.isEmpty()) || (password == null || password.isEmpty())) {
            throw new BadRequestException("error.user.login.required", messageService.getMessage("error.user.login.required"));
        }
        var auth = new UsernamePasswordAuthenticationToken(login, password);
        Authentication authentication;
        try {
            authentication = manager.authenticate(auth);
        } catch (CredentialsExpiredException e) {
            // right password, e-mail not verified yet (Auth#isCredentialsNonExpired)
            throw notVerified();
        }
        var principal = (Auth) authentication.getPrincipal();
        // F1-RN-03: antes da verificação do e-mail não há login (o front oferece "Reenviar e-mail")
        if (principal.getAccountingFirm() != null
                && firmAccessService.accessOf(principal.getAccountingFirm().getId()).pendingVerification()) {
            throw notVerified();
        }
        String token = tokenService.generateToken(principal);
        return new AuthenticatedDTO(token, principal.getId().toString(), principal.getUsername());
    }


    /**
     * {@code base64(login:senha)} split on the <b>first</b> {@code :} only (SN-RN-08, P-SN-10): the password may contain
     * {@code :} (a new login may not - error.user.login.invalid.char). Not base64 = 400.
     */
    static String[] splitCredentials(String encoded) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded == null ? "" : encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return new String[]{decoded};
        }
        return new String[]{decoded.substring(0, colon), decoded.substring(colon + 1)};
    }

    private ForbiddenException notVerified() {
        return new ForbiddenException("error.signup.email.not.verified",
                messageService.getMessage("error.signup.email.not.verified"));
    }
}
