package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.certidao.CertidaoDTOs;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.certidao.CertidaoService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Certidão SEFAZ-PE (M5): the carteira's column, the company's section, the kept PDF - and, with the permission to
 * sync notes, "Emitir agora" and the PDF issued by hand on e-Fisco.
 */
@RequiresPermission(Permission.COMPANIES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/certidoes")
public class CertidaoResource {

    private final CertidaoService service;

    public CertidaoResource(CertidaoService service) {
        this.service = service;
    }

    /** The newest certidão of each PE company of the firm the user can see. */
    @GetMapping("/carteira")
    public ResponseEntity<List<CertidaoDTOs.Summary>> carteira() {
        return ResponseEntity.ok(service.carteira());
    }

    @GetMapping("/empresa/{companyId}")
    public ResponseEntity<CertidaoDTOs.CompanyCertidao> ofCompany(@PathVariable String companyId) {
        return ResponseEntity.ok(service.ofCompany(companyId));
    }

    /** The certidão's PDF, to open in the browser. */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"certidao-pe.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(service.pdf(id));
    }

    /** Tries to issue the certidão on e-Fisco now; while e-Fisco asks for a captcha, says so (status ERRO). */
    @RequiresPermission(Permission.INVOICES_SYNC)
    @PostMapping("/empresa/{companyId}/emitir")
    public ResponseEntity<CertidaoDTOs.IssueResult> issue(@PathVariable String companyId) {
        return ResponseEntity.ok(service.issueNow(companyId));
    }

    /** The certidão PDF issued on e-Fisco by a person: read (number, issue, validity, situation) and kept. */
    @RequiresPermission(Permission.INVOICES_SYNC)
    @PostMapping(value = "/empresa/{companyId}/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CertidaoDTOs.CompanyCertidao> upload(@PathVariable String companyId,
                                                               @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        boolean empty = file == null || file.isEmpty();
        return ResponseEntity.ok(service.upload(companyId, empty ? null : file.getOriginalFilename(), empty ? null : file.getBytes()));
    }
}
