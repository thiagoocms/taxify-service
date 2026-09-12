package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.auth.Auth;
import br.com.cshub.taxify.dto.auth.AuthLoginDTO;
import br.com.cshub.taxify.dto.auth.AuthenticatedDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
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


    @Autowired
    public AuthService(TokenService tokenService, AuthenticationManager manager, MessageService messageService) {
        this.tokenService = tokenService;
        this.manager = manager;
        this.messageService = messageService;
    }

    public AuthenticatedDTO auth(AuthLoginDTO dto) {
        String[] decrypt = new String(Base64.getDecoder().decode(dto.getAuth())).split(":");
        if (decrypt.length < 2) {
            throw new BadRequestException();
        }
        var login = decrypt[0];
        var password = decrypt[1];
        if ((login == null || login.isEmpty()) || (password == null || password.isEmpty())) {
            throw new BadRequestException(messageService.getMessage("error.user.login.required"));
        }
        var auth = new UsernamePasswordAuthenticationToken(decrypt[0], decrypt[1]);
        Authentication authentication = manager.authenticate(auth);
        var principal = (Auth) authentication.getPrincipal();
        String token = tokenService.generateToken(principal);
        return new AuthenticatedDTO(token, principal.getId().toString(), principal.getUsername());
    }

}
