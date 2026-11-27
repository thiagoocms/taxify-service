package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.reform.IbsCbsRateChangeDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRateDTO;
import br.com.cshub.taxify.dto.reform.IbsCbsRatesDTO;
import br.com.cshub.taxify.security.AllowedWhenBlocked;
import br.com.cshub.taxify.security.PlatformAdminOnly;
import br.com.cshub.taxify.service.reform.IbsCbsRateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IBS/CBS reference rates per year in the platform console (spec 2026-11-27, F1a-0): only the platform admin (403 for
 * everyone else, the firm's ADMIN included - P-07, P-AL-02). The year is the id; {@code PUT} creates or updates (no
 * deletion: to take a year out of the conferência, its status goes to PENDING). Not on {@code AbstractCrudResource}:
 * the contract has no POST, no DELETE and a list with warnings instead of a page - the service and the validation are
 * the CRUD base's.
 */
@PlatformAdminOnly
@AllowedWhenBlocked
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/platform/ibs-cbs-rates")
public class PlatformIbsCbsRateResource {

    private final IbsCbsRateService service;

    public PlatformIbsCbsRateResource(IbsCbsRateService service) {
        this.service = service;
    }

    /** Every year, ascending, and the warnings of AL-RN-05. */
    @GetMapping
    public ResponseEntity<IbsCbsRatesDTO<IbsCbsRateDTO>> list() {
        return ResponseEntity.ok(service.platformList());
    }

    /** Create (body without version) or update (stored version) - AL-RN-03 and AL-RN-04. */
    @PutMapping("/{year}")
    public ResponseEntity<IbsCbsRateDTO> save(@PathVariable Integer year, @RequestBody IbsCbsRateDTO body) {
        return ResponseEntity.ok(service.save(year, body));
    }

    /** The year's changes, most recent first. */
    @GetMapping("/{year}/history")
    public ResponseEntity<List<IbsCbsRateChangeDTO>> history(@PathVariable Integer year) {
        return ResponseEntity.ok(service.history(year));
    }
}
