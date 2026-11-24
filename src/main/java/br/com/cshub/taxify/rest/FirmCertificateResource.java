package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.integration.FirmCertificateDTO;
import br.com.cshub.taxify.security.AdminOnly;
import br.com.cshub.taxify.service.FirmCertificateService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * The accountant's e-CPF of the current firm (Integrações → e-Fisco PE). GET is open to the firm's users (the
 * Fronteira shows whether the sync is on and how it last went); upload and removal are ADMIN only.
 */
@AdminOnly(writeOnly = true)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/integrations/efisco/certificado")
public class FirmCertificateResource {

    private final FirmCertificateService service;

    public FirmCertificateResource(FirmCertificateService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<FirmCertificateDTO> status() {
        return ResponseEntity.ok(service.statusForCurrentFirm());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FirmCertificateDTO> upload(@RequestParam("file") MultipartFile file,
                                                     @RequestParam("password") String password) throws IOException {
        return ResponseEntity.ok(service.uploadForCurrentFirm(file.getBytes(), password));
    }

    @DeleteMapping
    public ResponseEntity<FirmCertificateDTO> delete() {
        return ResponseEntity.ok(service.deleteForCurrentFirm());
    }
}
