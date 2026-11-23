package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.company.CompanyCertificateDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.service.CompanyCertificateService;
import br.com.cshub.taxify.service.SefazDistService;
import br.com.cshub.taxify.service.SefazNotesService;
import br.com.cshub.taxify.dto.company.SefazNotesDTO;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import br.com.cshub.taxify.service.CompanyRegistrationService;
import br.com.cshub.taxify.service.CompanyTaxRegimeService;
import br.com.cshub.taxify.dto.company.CompanyTaxRegimeDTO;
import br.com.cshub.taxify.security.AdminOnly;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import br.com.cshub.taxify.dto.company.CompanyFilterDTO;
import br.com.cshub.taxify.service.CompanyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RequiresPermission(Permission.COMPANIES_VIEW)
@AdminOnly(writeOnly = true)
@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/companies")
public class CompanyResource extends AbstractCrudResource<UUID, CompanyDTO, CompanyFilterDTO> {

    private final CompanyTaxRegimeService taxRegimeService;
    private final CompanyRegistrationService registrationService;
    private final CompanyCertificateService certificateService;
    private final SefazDistService sefazDistService;
    private final SefazNotesService sefazNotesService;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    @Autowired
    protected CompanyResource(CompanyService service, CompanyTaxRegimeService taxRegimeService,
                              CompanyRegistrationService registrationService, CompanyCertificateService certificateService,
                              SefazDistService sefazDistService, SefazNotesService sefazNotesService,
                              CompanyValidation companyValidation, MessageService messageService) {
        this.sefazNotesService = sefazNotesService;
        super(service);
        this.taxRegimeService = taxRegimeService;
        this.registrationService = registrationService;
        this.certificateService = certificateService;
        this.sefazDistService = sefazDistService;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    /** The A1 certificate used for the SEFAZ capture (own or the CNPJ root's) and how the last capture went. */
    @GetMapping("/{id}/certificado")
    public ResponseEntity<CompanyCertificateDTO> certificate(@PathVariable UUID id) {
        return ResponseEntity.ok(certificateService.status(id));
    }

    /** Uploads (or replaces) the company's A1: validated with the password, then stored encrypted. */
    @PostMapping(value = "/{id}/certificado", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CompanyCertificateDTO> uploadCertificate(@PathVariable UUID id,
                                                                   @RequestParam("file") MultipartFile file,
                                                                   @RequestParam("password") String password) throws IOException {
        return ResponseEntity.ok(certificateService.upload(id, file.getBytes(), password));
    }

    @DeleteMapping("/{id}/certificado")
    public ResponseEntity<CompanyCertificateDTO> deleteCertificate(@PathVariable UUID id) {
        return ResponseEntity.ok(certificateService.delete(id));
    }

    /** Runs the SEFAZ capture now (still respecting SEFAZ's one-hour wait) - only with SEFAZ as a source on the plan. */
    @PostMapping("/{id}/sefaz/capturar")
    public ResponseEntity<CompanyCertificateDTO> captureFromSefaz(@PathVariable UUID id) {
        Company company = companyValidation.checkExist(id);
        if (!certificateService.sefazInPlan(id)) {
            throw new BadRequestException(messageService.getMessage("error.sefaz.not.in.plan"));
        }
        sefazDistService.capture(company);
        return ResponseEntity.ok(certificateService.status(id));
    }

    /** Entries SEFAZ handed over only as a summary, the Ciência da Operação for them and the SIEG x SEFAZ comparison. */
    @GetMapping("/{id}/sefaz/resumos")
    public ResponseEntity<SefazNotesDTO> sefazSummaries(@PathVariable UUID id) {
        return ResponseEntity.ok(sefazNotesService.of(companyValidation.checkExist(id)));
    }

    /** Puts a refused ciência back in line - it goes with the next capture. */
    @PostMapping("/{id}/sefaz/resumos/{chave}/reenviar-ciencia")
    public ResponseEntity<SefazNotesDTO> retryCiencia(@PathVariable UUID id, @PathVariable String chave) {
        return ResponseEntity.ok(sefazNotesService.retryCiencia(companyValidation.checkExist(id), chave));
    }

    /** Regime periods of the company, oldest first. */
    @GetMapping("/{id}/regimes")
    public ResponseEntity<List<CompanyTaxRegimeDTO>> regimes(@PathVariable UUID id) {
        return ResponseEntity.ok(taxRegimeService.history(id));
    }

    /** Adds the period starting at validFrom (or replaces the one starting then). */
    @PostMapping("/{id}/regimes")
    public ResponseEntity<List<CompanyTaxRegimeDTO>> saveRegime(@PathVariable UUID id, @Valid @RequestBody CompanyTaxRegimeDTO dto) {
        return ResponseEntity.ok(taxRegimeService.save(id, dto));
    }

    @DeleteMapping("/{id}/regimes/{regimeId}")
    public ResponseEntity<List<CompanyTaxRegimeDTO>> deleteRegime(@PathVariable UUID id, @PathVariable UUID regimeId) {
        return ResponseEntity.ok(taxRegimeService.delete(id, regimeId));
    }

    /** Fills the empty registration fields (address, CNAE, IE, IM) from the company's latest own-issued note. */
    @PostMapping("/{id}/preencher-pelas-notas")
    public ResponseEntity<CompanyDTO> fillFromNotes(@PathVariable UUID id) {
        return ResponseEntity.ok(registrationService.fillFromOwnNotes(id));
    }
}
