package br.com.cshub.taxify.rest;

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

    @Autowired
    protected CompanyResource(CompanyService service, CompanyTaxRegimeService taxRegimeService,
                              CompanyRegistrationService registrationService) {
        super(service);
        this.taxRegimeService = taxRegimeService;
        this.registrationService = registrationService;
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
