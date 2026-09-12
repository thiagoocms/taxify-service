package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.auth.AuthLoginDTO;
import br.com.cshub.taxify.dto.auth.AuthenticatedDTO;
import br.com.cshub.taxify.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/auth")
public class AuthResource {

    private final AuthService authService;

    @Autowired
    public AuthResource(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<AuthenticatedDTO> auth(@RequestBody @Valid AuthLoginDTO dto) {

        var token = authService.auth(dto);

        return ResponseEntity
                .ok(token);
    }
}
